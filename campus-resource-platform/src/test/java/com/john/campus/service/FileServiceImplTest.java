package com.john.campus.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.john.campus.common.LoginUser;
import com.john.campus.common.RedisKeyConstants;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockMultipartFile;

/**
 * 文件秒传授权测试：全局内容命中不能替代当前用户的真实上传证明。
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
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private FileAuthorizationService fileAuthorizationService;

    private FileService fileService;

    @BeforeEach
    void setUp() {
        fileService = new FileServiceImpl(
                fileInfoMapper,
                fileStorageService,
                stringRedisTemplate,
                fileAuthorizationService);
        UserContextHolder.set(new LoginUser(USER_ID, 1, "file-authorization-test-jti"));
    }

    @AfterEach
    void clearContext() {
        UserContextHolder.clear();
    }

    @Test
    void md5CacheHitShouldNotRevealFileIdToUnauthorizedUser() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisKeyConstants.fileMd5Cache(MD5, SIZE)))
                .thenReturn(String.valueOf(FILE_ID));
        when(fileAuthorizationService.isAuthorized(USER_ID, FILE_ID)).thenReturn(false);

        FileCheckVO result = fileService.checkByMd5AndSize(MD5, SIZE);

        assertThat(result.secondUpload()).isFalse();
        assertThat(result.fileId()).isNull();
    }

    @Test
    void md5CacheHitShouldReturnFileIdToAuthorizedUser() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisKeyConstants.fileMd5Cache(MD5, SIZE)))
                .thenReturn(String.valueOf(FILE_ID));
        when(fileAuthorizationService.isAuthorized(USER_ID, FILE_ID)).thenReturn(true);

        FileCheckVO result = fileService.checkByMd5AndSize(MD5, SIZE);

        assertThat(result.secondUpload()).isTrue();
        assertThat(result.fileId()).isEqualTo(FILE_ID);
    }

    @Test
    void actualUploadHitShouldAuthorizeCurrentUserBeforeSecondUploadResponse() {
        MockMultipartFile upload = new MockMultipartFile(
                "file", "same.pdf", "application/pdf", new byte[]{1, 2, 3, 4});
        FileInfo existing = existingFile();
        when(fileStorageService.resolveExtension("same.pdf")).thenReturn("pdf");
        when(fileStorageService.calculateMd5(upload)).thenReturn(MD5);
        when(fileInfoMapper.selectByMd5AndSize(MD5, SIZE)).thenReturn(existing);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        FileUploadVO result = fileService.upload(upload);

        assertThat(result.fileId()).isEqualTo(FILE_ID);
        assertThat(result.secondUpload()).isTrue();
        verify(fileAuthorizationService).authorizeExistingFile(FILE_ID, USER_ID);
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
