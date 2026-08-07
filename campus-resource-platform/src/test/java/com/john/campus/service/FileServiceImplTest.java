package com.john.campus.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.john.campus.common.LoginUser;
import com.john.campus.common.UserContextHolder;
import com.john.campus.entity.FileInfo;
import com.john.campus.mapper.FileInfoMapper;
import com.john.campus.service.impl.FileServiceImpl;
import com.john.campus.vo.FileCheckVO;
import com.john.campus.vo.FileUploadVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockMultipartFile;

/**
 * 文件预检三态缓存与秒传授权测试：缓存只加速查询，不能绕过用户授权边界。
 */
@ExtendWith(MockitoExtension.class)
class FileServiceImplTest {

    private static final Long USER_ID = 10001L;
    private static final Long FILE_ID = 20001L;
    private static final String MD5 = "0123456789abcdef0123456789abcdef";
    private static final long SIZE = 4L;

    @Mock
    private FileInfoMapper fileInfoMapper;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private FileMd5CacheService fileMd5CacheService;
    @Mock
    private FileAuthorizationService fileAuthorizationService;

    private FileService fileService;

    @BeforeEach
    void setUp() {
        fileService = new FileServiceImpl(
                fileInfoMapper,
                fileStorageService,
                fileMd5CacheService,
                fileAuthorizationService);
        UserContextHolder.set(new LoginUser(USER_ID, 1, "file-authorization-test-jti"));
    }

    @AfterEach
    void clearContext() {
        UserContextHolder.clear();
    }

    @Test
    void foundCacheShouldNotRevealFileIdToUnauthorizedUserOrQueryFileInfo() {
        when(fileMd5CacheService.get(MD5, SIZE)).thenReturn(FileMd5CacheService.LookupResult.found(FILE_ID));
        when(fileAuthorizationService.isAuthorized(USER_ID, FILE_ID)).thenReturn(false);

        FileCheckVO result = fileService.checkByMd5AndSize(MD5, SIZE);

        assertThat(result.secondUpload()).isFalse();
        assertThat(result.fileId()).isNull();
        verifyNoInteractions(fileInfoMapper);
    }

    @Test
    void foundCacheShouldReturnFileIdToAuthorizedUserWithoutQueryingFileInfo() {
        when(fileMd5CacheService.get(MD5, SIZE)).thenReturn(FileMd5CacheService.LookupResult.found(FILE_ID));
        when(fileAuthorizationService.isAuthorized(USER_ID, FILE_ID)).thenReturn(true);

        FileCheckVO result = fileService.checkByMd5AndSize(MD5, SIZE);

        assertThat(result.secondUpload()).isTrue();
        assertThat(result.fileId()).isEqualTo(FILE_ID);
        verifyNoInteractions(fileInfoMapper);
    }

    @Test
    void notFoundCacheShouldReturnMissWithoutQueryingDatabaseOrAuthorization() {
        when(fileMd5CacheService.get(MD5, SIZE)).thenReturn(FileMd5CacheService.LookupResult.notFound());

        FileCheckVO result = fileService.checkByMd5AndSize(MD5, SIZE);

        assertThat(result.secondUpload()).isFalse();
        assertThat(result.fileId()).isNull();
        verifyNoInteractions(fileInfoMapper, fileAuthorizationService);
    }

    @Test
    void absentCacheAndDatabaseMissShouldWriteNegativeCache() {
        when(fileMd5CacheService.get(MD5, SIZE)).thenReturn(FileMd5CacheService.LookupResult.absent());
        when(fileInfoMapper.selectByMd5AndSize(MD5, SIZE)).thenReturn(null);

        FileCheckVO result = fileService.checkByMd5AndSize(MD5, SIZE);

        assertThat(result.secondUpload()).isFalse();
        assertThat(result.fileId()).isNull();
        verify(fileMd5CacheService).putNotFound(MD5, SIZE);
        verifyNoInteractions(fileAuthorizationService);
    }

    @Test
    void absentCacheAndDatabaseHitShouldWritePositiveCacheThenEnforceAuthorization() {
        FileInfo existing = existingFile();
        when(fileMd5CacheService.get(MD5, SIZE)).thenReturn(FileMd5CacheService.LookupResult.absent());
        when(fileInfoMapper.selectByMd5AndSize(MD5, SIZE)).thenReturn(existing);
        when(fileAuthorizationService.isAuthorized(USER_ID, FILE_ID)).thenReturn(false);

        FileCheckVO result = fileService.checkByMd5AndSize(MD5, SIZE);

        assertThat(result.secondUpload()).isFalse();
        assertThat(result.fileId()).isNull();
        verify(fileMd5CacheService).putFound(MD5, SIZE, FILE_ID);
        verify(fileAuthorizationService).isAuthorized(USER_ID, FILE_ID);
        verify(fileMd5CacheService, never()).putNotFound(MD5, SIZE);
    }

    @Test
    void actualUploadHitShouldAuthorizeCurrentUserAndWritePositiveCache() {
        MockMultipartFile upload = uploadFile();
        FileInfo existing = existingFile();
        when(fileStorageService.resolveExtension("same.pdf")).thenReturn("pdf");
        when(fileStorageService.calculateMd5(upload)).thenReturn(MD5);
        when(fileInfoMapper.selectByMd5AndSize(MD5, SIZE)).thenReturn(existing);

        FileUploadVO result = fileService.upload(upload);

        assertThat(result.fileId()).isEqualTo(FILE_ID);
        assertThat(result.secondUpload()).isTrue();
        verify(fileAuthorizationService).authorizeExistingFile(FILE_ID, USER_ID);
        verify(fileMd5CacheService).putFound(MD5, SIZE, FILE_ID);
    }

    @Test
    void existingFileAuthorizationFailureShouldPropagateWithoutWritingPositiveCache() {
        MockMultipartFile upload = uploadFile();
        RuntimeException authorizationFailure = new RuntimeException("authorization transaction failed");
        when(fileStorageService.resolveExtension("same.pdf")).thenReturn("pdf");
        when(fileStorageService.calculateMd5(upload)).thenReturn(MD5);
        when(fileInfoMapper.selectByMd5AndSize(MD5, SIZE)).thenReturn(existingFile());
        doThrow(authorizationFailure)
                .when(fileAuthorizationService).authorizeExistingFile(FILE_ID, USER_ID);

        assertThatThrownBy(() -> fileService.upload(upload)).isSameAs(authorizationFailure);

        // 授权事务失败时不能发布正缓存，避免缓存先于数据库事实对外可见。
        verify(fileMd5CacheService, never()).putFound(anyString(), anyLong(), any());
    }

    @Test
    void newFileTransactionFailureShouldDeleteStoredFileWithoutWritingPositiveCache() {
        MockMultipartFile upload = uploadFile();
        FileStorageService.StoredFile stored = stubNewFileUpload(upload);
        RuntimeException transactionFailure = new RuntimeException("create transaction failed");
        doThrow(transactionFailure)
                .when(fileAuthorizationService).createAuthorizedFile(any(FileInfo.class), eq(USER_ID));

        assertThatThrownBy(() -> fileService.upload(upload)).isSameAs(transactionFailure);

        verify(fileStorageService).delete(stored.storagePath());
        verify(fileMd5CacheService, never()).putFound(anyString(), anyLong(), any());
    }

    @Test
    void duplicateKeyFallbackSuccessShouldAuthorizeConcurrentFileAndWritePositiveCache() {
        MockMultipartFile upload = uploadFile();
        FileStorageService.StoredFile stored = stubNewFileUpload(upload);
        when(fileInfoMapper.selectByMd5AndSize(MD5, SIZE)).thenReturn(null, existingFile());
        doThrow(new DuplicateKeyException("concurrent insert"))
                .when(fileAuthorizationService).createAuthorizedFile(any(FileInfo.class), eq(USER_ID));

        FileUploadVO result = fileService.upload(upload);

        assertThat(result.fileId()).isEqualTo(FILE_ID);
        assertThat(result.secondUpload()).isTrue();
        verify(fileStorageService).delete(stored.storagePath());
        verify(fileAuthorizationService).authorizeExistingFile(FILE_ID, USER_ID);
        verify(fileMd5CacheService).putFound(MD5, SIZE, FILE_ID);
    }

    private FileStorageService.StoredFile stubNewFileUpload(MockMultipartFile upload) {
        FileStorageService.StoredFile stored = new FileStorageService.StoredFile("stored.pdf", "uploads/stored.pdf");
        when(fileStorageService.resolveExtension("same.pdf")).thenReturn("pdf");
        when(fileStorageService.calculateMd5(upload)).thenReturn(MD5);
        when(fileStorageService.store(upload, "pdf")).thenReturn(stored);
        return stored;
    }

    private MockMultipartFile uploadFile() {
        return new MockMultipartFile(
                "file", "same.pdf", "application/pdf", new byte[]{1, 2, 3, 4});
    }

    private FileInfo existingFile() {
        FileInfo fileInfo = new FileInfo();
        fileInfo.setId(FILE_ID);
        fileInfo.setFileMd5(MD5);
        fileInfo.setFileSize(SIZE);
        fileInfo.setOriginalName("same.pdf");
        fileInfo.setFileExt("pdf");
        fileInfo.setStatus(FileInfo.STATUS_NORMAL);
        return fileInfo;
    }
}
