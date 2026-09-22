package com.john.campus.service.impl;

import com.john.campus.common.ErrorCode;
import com.john.campus.exception.BusinessException;
import com.john.campus.service.FileContentValidator;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * 基于 JDK 的轻量文件内容校验器，不执行宏或脚本，也不把压缩包内容写入磁盘。
 */
@Component
public class FileContentValidatorImpl implements FileContentValidator {

    private static final int MAGIC_PREFIX_LENGTH = 16;
    private static final int MAX_ZIP_ENTRIES = 1024;
    private static final long MAX_ZIP_ENTRY_BYTES = 32L * 1024 * 1024;
    private static final long MAX_ZIP_TOTAL_BYTES = 64L * 1024 * 1024;
    private static final int MAX_COMPRESSION_RATIO = 100;
    private static final int MAX_OOXML_METADATA_BYTES = 512 * 1024;
    private static final int ZIP_END_RECORD_MAX_BYTES = 65_557;
    private static final byte[] ZIP_LOCAL_HEADER = {0x50, 0x4B, 0x03, 0x04};
    private static final byte[] ZIP_EMPTY_HEADER = {0x50, 0x4B, 0x05, 0x06};
    private static final String OFFICE_DOCUMENT_RELATIONSHIP =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument";
    private static final String STRICT_OFFICE_DOCUMENT_RELATIONSHIP =
            "http://purl.oclc.org/ooxml/officeDocument/relationships/officeDocument";

    /**
     * 每种扩展名只接受明确的浏览器 MIME；application/octet-stream 由统一逻辑额外放行。
     */
    private static final Map<String, TypeRule> TYPE_RULES = Map.ofEntries(
            Map.entry("pdf", rule("application/pdf", Set.of("application/pdf"),
                    prefix -> startsWith(prefix, "%PDF-".getBytes(StandardCharsets.US_ASCII)))),
            Map.entry("jpg", rule("image/jpeg", Set.of("image/jpeg", "image/jpg"),
                    FileContentValidatorImpl::isJpeg)),
            Map.entry("jpeg", rule("image/jpeg", Set.of("image/jpeg", "image/jpg"),
                    FileContentValidatorImpl::isJpeg)),
            Map.entry("png", rule("image/png", Set.of("image/png"), FileContentValidatorImpl::isPng)),
            Map.entry("7z", rule("application/x-7z-compressed", Set.of("application/x-7z-compressed"),
                    FileContentValidatorImpl::isSevenZip)),
            Map.entry("rar", rule("application/vnd.rar", Set.of("application/vnd.rar", "application/x-rar-compressed"),
                    FileContentValidatorImpl::isRar)),
            Map.entry("doc", rule("application/msword", Set.of("application/msword"),
                    FileContentValidatorImpl::isOle)),
            Map.entry("xls", rule("application/vnd.ms-excel", Set.of("application/vnd.ms-excel"),
                    FileContentValidatorImpl::isOle)),
            Map.entry("ppt", rule("application/vnd.ms-powerpoint", Set.of("application/vnd.ms-powerpoint"),
                    FileContentValidatorImpl::isOle)),
            Map.entry("zip", rule("application/zip", Set.of("application/zip", "application/x-zip-compressed"),
                    FileContentValidatorImpl::isZip)),
            Map.entry("docx", rule("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                    FileContentValidatorImpl::isZip)),
            Map.entry("xlsx", rule("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    Set.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                    FileContentValidatorImpl::isZip)),
            Map.entry("pptx", rule("application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    Set.of("application/vnd.openxmlformats-officedocument.presentationml.presentation"),
                    FileContentValidatorImpl::isZip)),
            Map.entry("txt", rule("text/plain", Set.of("text/plain"), prefix -> true)),
            Map.entry("md", rule("text/markdown", Set.of("text/markdown", "text/plain"), prefix -> true)));

    @Override
    public ValidatedFileType validate(MultipartFile file, String fileExtension) {
        String extension = fileExtension == null ? "" : fileExtension.toLowerCase(Locale.ROOT);
        TypeRule rule = TYPE_RULES.get(extension);
        if (rule == null) {
            throw invalidType();
        }
        validateClientMime(file.getContentType(), rule.allowedClientMimeTypes());

        try {
            byte[] prefix = readPrefix(file);
            if (!rule.magicMatcher().test(prefix)) {
                throw invalidType();
            }
            if ("txt".equals(extension) || "md".equals(extension)) {
                validateText(file);
            } else if (isZipExtension(extension)) {
                validateZipContainer(file, extension);
            }
            return new ValidatedFileType(rule.serverMimeType());
        } catch (ZipException | CharacterCodingException ex) {
            throw invalidType();
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.SERVER_ERROR, "文件读取失败");
        }
    }

    private void validateClientMime(String contentType, Set<String> allowedTypes) {
        if (!StringUtils.hasText(contentType)) {
            throw invalidType();
        }
        String normalized = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!"application/octet-stream".equals(normalized) && !allowedTypes.contains(normalized)) {
            throw invalidType();
        }
    }

    private byte[] readPrefix(MultipartFile file) throws IOException {
        try (InputStream inputStream = file.getInputStream()) {
            return inputStream.readNBytes(MAGIC_PREFIX_LENGTH);
        }
    }

    /**
     * 文本必须整体通过严格 UTF-8 解码；NUL 和过多控制字符用于识别伪装成文本的二进制内容。
     */
    private void validateText(MultipartFile file) throws IOException {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        long characterCount = 0;
        long suspiciousControls = 0;
        try (Reader reader = new InputStreamReader(file.getInputStream(), decoder)) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                for (int index = 0; index < read; index++) {
                    char current = buffer[index];
                    if (current == '\0') {
                        throw invalidType();
                    }
                    if (Character.isISOControl(current)
                            && current != '\n' && current != '\r' && current != '\t' && current != '\f') {
                        suspiciousControls++;
                    }
                    characterCount++;
                }
            }
        }
        if (suspiciousControls > 4 && suspiciousControls * 100 > characterCount) {
            throw invalidType();
        }
    }

    /**
     * 流式扫描 ZIP，不落盘解压；条目数、单条目和总解压量形成硬上限，阻断典型 ZIP bomb。
     */
    private void validateZipContainer(MultipartFile file, String extension) throws IOException {
        if (!hasValidZipEndRecord(file)) {
            throw invalidType();
        }
        int entryCount = 0;
        long totalUncompressed = 0;
        OoxmlRule ooxmlRule = ooxmlRule(extension);
        boolean expectedContentType = false;
        boolean rootOfficeDocumentRelationship = false;
        boolean requiredOoxmlPart = false;
        byte[] buffer = new byte[8192];

        try (ZipInputStream zipInputStream = new ZipInputStream(file.getInputStream(), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ZIP_ENTRIES || unsafeZipEntryName(entry.getName())) {
                    throw invalidType();
                }
                if (entry.getSize() > MAX_ZIP_ENTRY_BYTES) {
                    throw invalidType();
                }

                String entryName = entry.getName().replace('\\', '/');
                if (ooxmlRule != null && entryName.toLowerCase(Locale.ROOT).endsWith("vbaproject.bin")) {
                    throw invalidType();
                }
                requiredOoxmlPart |= ooxmlRule != null && ooxmlRule.mainPart().equals(entryName);

                boolean metadataEntry = ooxmlRule != null
                        && ("[Content_Types].xml".equals(entryName) || entryName.endsWith(".rels"));
                ByteArrayOutputStream metadata = metadataEntry ? new ByteArrayOutputStream() : null;

                long entryBytes = 0;
                int read;
                while ((read = zipInputStream.read(buffer)) != -1) {
                    entryBytes += read;
                    totalUncompressed += read;
                    if (entryBytes > MAX_ZIP_ENTRY_BYTES || totalUncompressed > MAX_ZIP_TOTAL_BYTES) {
                        throw invalidType();
                    }
                    if (metadata != null) {
                        if (entryBytes > MAX_OOXML_METADATA_BYTES) {
                            throw invalidType();
                        }
                        metadata.write(buffer, 0, read);
                    }
                }
                long compressedSize = entry.getCompressedSize();
                if (entryBytes > 1024 * 1024 && compressedSize > 0
                        && entryBytes / compressedSize > MAX_COMPRESSION_RATIO) {
                    throw invalidType();
                }
                if (metadata != null && "[Content_Types].xml".equals(entryName)) {
                    expectedContentType = validateContentTypes(metadata.toByteArray(), ooxmlRule);
                } else if (metadata != null) {
                    boolean rootRelationship = "_rels/.rels".equals(entryName);
                    boolean hasExpectedRootRelationship = validateRelationships(
                            metadata.toByteArray(), ooxmlRule, rootRelationship);
                    rootOfficeDocumentRelationship |= rootRelationship && hasExpectedRootRelationship;
                }
                zipInputStream.closeEntry();
            }
        }

        // 普通 ZIP 至少包含一个可解析条目；OOXML 还必须由内容类型和根关系共同声明对应主文档。
        if (entryCount == 0 || (ooxmlRule != null
                && (!expectedContentType || !rootOfficeDocumentRelationship || !requiredOoxmlPart))) {
            throw invalidType();
        }
    }

    /**
     * 校验 OOXML 内容类型声明，拒绝宏类型并确认主文档部件与扩展名一致。
     */
    private boolean validateContentTypes(byte[] xml, OoxmlRule rule) {
        Document document = parseSecureXml(xml);
        boolean expected = false;
        NodeList elements = document.getElementsByTagName("*");
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            String contentType = element.getAttribute("ContentType");
            String normalizedType = contentType.toLowerCase(Locale.ROOT);
            if (normalizedType.contains("macroenabled") || normalizedType.contains("vbaproject")) {
                throw invalidType();
            }
            String localName = element.getLocalName() == null ? element.getTagName() : element.getLocalName();
            if ("Override".equals(localName)
                    && normalizeOoxmlPart(element.getAttribute("PartName")).equals(rule.mainPart())
                    && rule.mainContentType().equals(contentType)) {
                expected = true;
            }
        }
        return expected;
    }

    /**
     * 所有关系文件都禁止外部目标；根关系还必须指向当前扩展名对应的主文档。
     */
    private boolean validateRelationships(byte[] xml, OoxmlRule rule, boolean rootRelationshipFile) {
        Document document = parseSecureXml(xml);
        boolean expectedRoot = false;
        NodeList relationships = document.getElementsByTagNameNS("*", "Relationship");
        for (int index = 0; index < relationships.getLength(); index++) {
            Element relationship = (Element) relationships.item(index);
            if ("External".equalsIgnoreCase(relationship.getAttribute("TargetMode"))) {
                throw invalidType();
            }
            String type = relationship.getAttribute("Type");
            if (rootRelationshipFile
                    && (OFFICE_DOCUMENT_RELATIONSHIP.equals(type)
                            || STRICT_OFFICE_DOCUMENT_RELATIONSHIP.equals(type))
                    && normalizeOoxmlPart(relationship.getAttribute("Target")).equals(rule.mainPart())) {
                expectedRoot = true;
            }
        }
        return expectedRoot;
    }

    /**
     * XML 解析禁用 DTD、外部实体与 XInclude，避免 OOXML 元数据触发 XXE 或实体扩展。
     */
    private Document parseSecureXml(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (ParserConfigurationException | SAXException | IOException ex) {
            throw invalidType();
        }
    }

    private String normalizeOoxmlPart(String partName) {
        String normalized = partName == null ? "" : partName.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    /**
     * ZIP 中央目录结束记录最多距文件尾 65557 字节；只保留该窗口即可确认容器没有被截断。
     */
    private boolean hasValidZipEndRecord(MultipartFile file) throws IOException {
        byte[] tail = new byte[ZIP_END_RECORD_MAX_BYTES];
        long totalBytes = 0;
        int writeIndex = 0;
        try (InputStream inputStream = file.getInputStream()) {
            byte[] readBuffer = new byte[8192];
            int read;
            while ((read = inputStream.read(readBuffer)) != -1) {
                for (int index = 0; index < read; index++) {
                    tail[writeIndex] = readBuffer[index];
                    writeIndex = (writeIndex + 1) % tail.length;
                }
                totalBytes += read;
            }
        }
        int tailLength = (int) Math.min(totalBytes, tail.length);
        int logicalStart = totalBytes <= tail.length ? 0 : writeIndex;
        for (int index = 0; index <= tailLength - 22; index++) {
            if (tailByte(tail, logicalStart, index) == 0x50
                    && tailByte(tail, logicalStart, index + 1) == 0x4B
                    && tailByte(tail, logicalStart, index + 2) == 0x05
                    && tailByte(tail, logicalStart, index + 3) == 0x06) {
                int commentLength = tailByte(tail, logicalStart, index + 20)
                        | (tailByte(tail, logicalStart, index + 21) << 8);
                if (index + 22 + commentLength == tailLength) {
                    return true;
                }
            }
        }
        return false;
    }

    private int tailByte(byte[] tail, int logicalStart, int logicalIndex) {
        return unsigned(tail[(logicalStart + logicalIndex) % tail.length]);
    }

    private boolean unsafeZipEntryName(String name) {
        if (!StringUtils.hasText(name)) {
            return true;
        }
        String normalized = name.replace('\\', '/');
        return normalized.startsWith("/")
                || normalized.matches("^[A-Za-z]:.*")
                || Arrays.asList(normalized.split("/", -1)).contains("..");
    }

    private OoxmlRule ooxmlRule(String extension) {
        return switch (extension) {
            case "docx" -> new OoxmlRule(
                    "word/document.xml",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml");
            case "xlsx" -> new OoxmlRule(
                    "xl/workbook.xml",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml");
            case "pptx" -> new OoxmlRule(
                    "ppt/presentation.xml",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml");
            default -> null;
        };
    }

    private static boolean isZipExtension(String extension) {
        return "zip".equals(extension) || "docx".equals(extension)
                || "xlsx".equals(extension) || "pptx".equals(extension);
    }

    private static boolean isJpeg(byte[] prefix) {
        return prefix.length >= 3
                && unsigned(prefix[0]) == 0xFF && unsigned(prefix[1]) == 0xD8 && unsigned(prefix[2]) == 0xFF;
    }

    private static boolean isPng(byte[] prefix) {
        return startsWith(prefix, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
    }

    private static boolean isSevenZip(byte[] prefix) {
        return startsWith(prefix, new byte[]{0x37, 0x7A, (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C});
    }

    private static boolean isRar(byte[] prefix) {
        byte[] common = {0x52, 0x61, 0x72, 0x21, 0x1A, 0x07};
        return startsWith(prefix, common) && prefix.length >= 7
                && (prefix[6] == 0x00 || (prefix[6] == 0x01 && prefix.length >= 8 && prefix[7] == 0x00));
    }

    private static boolean isOle(byte[] prefix) {
        return startsWith(prefix, new byte[]{
                (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1});
    }

    private static boolean isZip(byte[] prefix) {
        return startsWith(prefix, ZIP_LOCAL_HEADER)
                || startsWith(prefix, ZIP_EMPTY_HEADER);
    }

    private static boolean startsWith(byte[] content, byte[] signature) {
        if (content.length < signature.length) {
            return false;
        }
        for (int index = 0; index < signature.length; index++) {
            if (content[index] != signature[index]) {
                return false;
            }
        }
        return true;
    }

    private static int unsigned(byte value) {
        return value & 0xFF;
    }

    private static TypeRule rule(String serverMimeType, Set<String> allowedMimeTypes, Predicate<byte[]> matcher) {
        return new TypeRule(serverMimeType, allowedMimeTypes, matcher);
    }

    private static BusinessException invalidType() {
        return new BusinessException(ErrorCode.FILE_TYPE_NOT_ALLOWED, "文件内容、扩展名或 MIME 不匹配");
    }

    private record TypeRule(
            String serverMimeType,
            Set<String> allowedClientMimeTypes,
            Predicate<byte[]> magicMatcher) {
    }

    private record OoxmlRule(String mainPart, String mainContentType) {
    }
}
