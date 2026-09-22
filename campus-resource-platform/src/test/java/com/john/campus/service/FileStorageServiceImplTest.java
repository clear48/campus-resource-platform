package com.john.campus.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.john.campus.common.ErrorCode;
import com.john.campus.exception.BusinessException;
import com.john.campus.service.impl.FileStorageServiceImpl;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * 本地文件存储测试，验证磁盘水位、完整文件发布和失败临时文件清理。
 */
class FileStorageServiceImplTest {

    @TempDir
    Path tempDir;

    @Test
    void storeShouldPublishCompleteFileWithoutPartResidue() throws IOException {
        byte[] content = "complete-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        FileStorageService service = new FileStorageServiceImpl(tempDir.toString(), 1L);

        FileStorageService.StoredFile stored = service.store(
                new MockMultipartFile("file", "a.txt", "text/plain", content), "txt");

        assertThat(Path.of(stored.storagePath())).hasBinaryContent(content);
        try (Stream<Path> paths = Files.list(tempDir)) {
            assertThat(paths.noneMatch(path -> path.getFileName().toString().endsWith(".part"))).isTrue();
        }
    }

    @Test
    void insufficientSpaceShouldUseDedicatedErrorAndWriteNothing() {
        FileStorageService service = new FileStorageServiceImpl(tempDir.toString(), Long.MAX_VALUE);
        MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain", new byte[]{1});

        assertThatThrownBy(() -> service.store(file, "txt"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.STORAGE_INSUFFICIENT.getCode()));
        assertDirectoryEmpty();
    }

    @Test
    void inputFailureShouldCleanTemporaryFile() throws IOException {
        FileStorageService service = new FileStorageServiceImpl(tempDir.toString(), 1L);
        MultipartFile brokenFile = mock(MultipartFile.class);
        when(brokenFile.getSize()).thenReturn(3L);
        when(brokenFile.getInputStream()).thenAnswer(invocation -> failingInputStream());

        assertThatThrownBy(() -> service.store(brokenFile, "txt"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.SERVER_ERROR.getCode()));
        assertDirectoryEmpty();
    }

    private InputStream failingInputStream() {
        return new InputStream() {
            private int reads;

            @Override
            public int read() throws IOException {
                if (reads++ < 2) {
                    return 'a';
                }
                throw new IOException("simulated read failure");
            }
        };
    }

    private void assertDirectoryEmpty() {
        try (Stream<Path> paths = Files.list(tempDir)) {
            assertThat(paths).isEmpty();
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
    }
}
