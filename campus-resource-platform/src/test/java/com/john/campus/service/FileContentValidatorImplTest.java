package com.john.campus.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.john.campus.common.ErrorCode;
import com.john.campus.exception.BusinessException;
import com.john.campus.service.impl.FileContentValidatorImpl;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockMultipartFile;

/**
 * 文件内容校验测试，覆盖固定 magic、MIME、文本、ZIP/OOXML 结构和压缩炸弹边界。
 */
class FileContentValidatorImplTest {

    private final FileContentValidator validator = new FileContentValidatorImpl();

    @ParameterizedTest
    @MethodSource("validMagicFiles")
    void supportedMagicShouldReturnControlledServerMime(
            String extension, String clientMime, byte[] content, String expectedMime) {
        MockMultipartFile file = file("sample." + extension, clientMime, content);

        FileContentValidator.ValidatedFileType result = validator.validate(file, extension);

        assertThat(result.mimeType()).isEqualTo(expectedMime);
    }

    @Test
    void octetStreamShouldBeAcceptedButMismatchedMimeShouldBeRejected() {
        byte[] pdf = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
        assertThat(validator.validate(file("a.pdf", "application/octet-stream", pdf), "pdf").mimeType())
                .isEqualTo("application/pdf");

        assertFileTypeRejected(() -> validator.validate(file("a.pdf", "image/png", pdf), "pdf"));
    }

    @Test
    void extensionAndMagicMismatchShouldBeRejected() {
        assertFileTypeRejected(() -> validator.validate(
                file("fake.pdf", "application/pdf", "not-a-pdf".getBytes(StandardCharsets.UTF_8)), "pdf"));
    }

    @Test
    void validUtf8TextShouldPassWhileNulMalformedUtf8AndBinaryControlsAreRejected() {
        assertThat(validator.validate(
                file("note.md", "text/plain; charset=UTF-8", "# 中文笔记\n正文".getBytes(StandardCharsets.UTF_8)),
                "md").mimeType()).isEqualTo("text/markdown");

        assertFileTypeRejected(() -> validator.validate(
                file("note.txt", "text/plain", new byte[]{'a', 0, 'b'}), "txt"));
        assertFileTypeRejected(() -> validator.validate(
                file("note.txt", "text/plain", new byte[]{(byte) 0xC3, 0x28}), "txt"));
        assertFileTypeRejected(() -> validator.validate(
                file("note.txt", "text/plain", new byte[]{1, 2, 3, 4, 5, 'a'}), "txt"));
    }

    @Test
    void zipAndValidOoxmlStructuresShouldPass() throws IOException {
        byte[] zip = zip(Map.of("notes/readme.txt", "hello".getBytes(StandardCharsets.UTF_8)));
        assertThat(validator.validate(file("a.zip", "application/zip", zip), "zip").mimeType())
                .isEqualTo("application/zip");

        Map<String, byte[]> docxEntries = validOoxmlEntries(
                "word/document.xml",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml");
        byte[] docx = zip(docxEntries);
        assertThat(validator.validate(file(
                "a.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docx), "docx").mimeType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    @Test
    void ooxmlMissingRequiredStructureOrUnsafeEntryShouldBeRejected() throws IOException {
        byte[] missingStructure = zip(Map.of(
                "[Content_Types].xml", "<Types/>".getBytes(StandardCharsets.UTF_8),
                "custom/data.xml", "x".getBytes(StandardCharsets.UTF_8)));
        assertFileTypeRejected(() -> validator.validate(file(
                "a.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                missingStructure), "docx"));

        byte[] traversal = zip(Map.of("../outside.txt", "x".getBytes(StandardCharsets.UTF_8)));
        assertFileTypeRejected(() -> validator.validate(file("a.zip", "application/zip", traversal), "zip"));
    }

    @Test
    void ooxmlPlaceholderEntriesShouldNotImpersonateOfficeDocument() throws IOException {
        Map<String, byte[]> forged = new LinkedHashMap<>();
        forged.put("[Content_Types].xml", bytes("<Types/>"));
        forged.put("word/document.xml", bytes("<document/>"));

        assertFileTypeRejected(() -> validator.validate(file(
                "fake.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                zip(forged)), "docx"));
    }

    @Test
    void macroAndExternalRelationshipsShouldBeRejected() throws IOException {
        Map<String, byte[]> macro = validOoxmlEntries(
                "word/document.xml",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml");
        macro.put("word/vbaProject.bin", new byte[]{1, 2, 3});
        assertFileTypeRejected(() -> validator.validate(file(
                "macro.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                zip(macro)), "docx"));

        Map<String, byte[]> external = validOoxmlEntries(
                "word/document.xml",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml");
        external.put("word/_rels/document.xml.rels", bytes("""
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://example.test/link"
                                Target="https://example.test/payload" TargetMode="External"/>
                </Relationships>
                """));
        assertFileTypeRejected(() -> validator.validate(file(
                "external.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                zip(external)), "docx"));
    }

    @ParameterizedTest
    @MethodSource("validSpreadsheetAndPresentationOoxml")
    void spreadsheetAndPresentationOoxmlShouldRequireTheirKeyStructure(
            String extension, String mime, String keyEntry) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.putAll(validOoxmlEntries(keyEntry, switch (extension) {
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml";
            default -> throw new IllegalArgumentException("unexpected extension");
        }));

        assertThat(validator.validate(file("a." + extension, mime, zip(entries)), extension).mimeType())
                .isEqualTo(mime);
    }

    @Test
    void zipWithLocalHeaderButNoCentralDirectoryShouldBeRejected() {
        byte[] truncated = {0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0};

        assertFileTypeRejected(() -> validator.validate(file("a.zip", "application/zip", truncated), "zip"));
    }

    @Test
    void excessiveCompressionRatioShouldBeRejected() throws IOException {
        byte[] highlyCompressible = new byte[2 * 1024 * 1024];
        byte[] archive = zip(Map.of("large.bin", highlyCompressible));

        assertFileTypeRejected(() -> validator.validate(file("bomb.zip", "application/zip", archive), "zip"));
    }

    private static Stream<Arguments> validMagicFiles() {
        byte[] ole = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0};
        return Stream.of(
                Arguments.of("pdf", "application/pdf", bytes("%PDF-1.7"), "application/pdf"),
                Arguments.of("jpg", "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0}, "image/jpeg"),
                Arguments.of("png", "image/png", new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}, "image/png"),
                Arguments.of("7z", "application/x-7z-compressed", new byte[]{0x37, 0x7A, (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C}, "application/x-7z-compressed"),
                Arguments.of("rar", "application/vnd.rar", new byte[]{0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00}, "application/vnd.rar"),
                Arguments.of("doc", "application/msword", ole, "application/msword"),
                Arguments.of("xls", "application/vnd.ms-excel", ole, "application/vnd.ms-excel"),
                Arguments.of("ppt", "application/vnd.ms-powerpoint", ole, "application/vnd.ms-powerpoint"));
    }

    private static Stream<Arguments> validSpreadsheetAndPresentationOoxml() {
        return Stream.of(
                Arguments.of(
                        "xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "xl/workbook.xml"),
                Arguments.of(
                        "pptx",
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                        "ppt/presentation.xml"));
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
                ZipOutputStream zipOutput = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zipOutput.putNextEntry(new ZipEntry(entry.getKey()));
                zipOutput.write(entry.getValue());
                zipOutput.closeEntry();
            }
            zipOutput.finish();
            return output.toByteArray();
        }
    }

    private static Map<String, byte[]> validOoxmlEntries(String mainPart, String mainContentType) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", bytes("""
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Override PartName="/%s" ContentType="%s"/>
                </Types>
                """.formatted(mainPart, mainContentType)));
        entries.put("_rels/.rels", bytes("""
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1"
                    Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
                    Target="%s"/>
                </Relationships>
                """.formatted(mainPart)));
        entries.put(mainPart, bytes("<root/>"));
        return entries;
    }

    private static MockMultipartFile file(String name, String mime, byte[] content) {
        return new MockMultipartFile("file", name, mime, content);
    }

    private static byte[] bytes(String content) {
        return content.getBytes(StandardCharsets.US_ASCII);
    }

    private void assertFileTypeRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.FILE_TYPE_NOT_ALLOWED.getCode()));
    }
}
