package com.john.campus.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.john.campus.common.LoginUser;
import com.john.campus.common.RedisKeyConstants;
import com.john.campus.common.UserContextHolder;
import com.john.campus.entity.FileInfo;
import com.john.campus.entity.Resource;
import com.john.campus.mapper.DownloadRecordMapper;
import com.john.campus.mapper.FileInfoMapper;
import com.john.campus.mapper.ResourceMapper;
import com.john.campus.service.impl.DownloadServiceImpl;
import com.john.campus.vo.DownloadTicketVO;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 下载热度联动测试：热度必须依赖既有去重成功结果，不能因重复下载或排行榜异常影响下载凭证创建。
 */
@ExtendWith(MockitoExtension.class)
class DownloadServiceImplTest {

    @Mock
    private DownloadRecordMapper downloadRecordMapper;
    @Mock
    private ResourceMapper resourceMapper;
    @Mock
    private FileInfoMapper fileInfoMapper;
    @Mock
    private DownloadRateLimiter downloadRateLimiter;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private RankingService rankingService;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private DownloadService downloadService;

    @BeforeEach
    void setUp() {
        downloadService = new DownloadServiceImpl(
                downloadRecordMapper,
                resourceMapper,
                fileInfoMapper,
                downloadRateLimiter,
                fileStorageService,
                stringRedisTemplate,
                rankingService);
        UserContextHolder.set(new LoginUser(10001L, 1, "download-heat-test-jti"));
        when(resourceMapper.selectById(100L)).thenReturn(approvedResource());
        when(fileInfoMapper.selectNormalById(200L)).thenReturn(normalFile());
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @AfterEach
    void clearUserContext() {
        UserContextHolder.clear();
    }

    @Test
    void firstDeduplicatedDownloadShouldRecordHeat() {
        when(valueOperations.setIfAbsent(
                RedisKeyConstants.downloadDedup(10001L, 100L), "1", 600L, TimeUnit.SECONDS)).thenReturn(true);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.increment(RedisKeyConstants.DOWNLOAD_DELTA, "100", 1)).thenReturn(1L);

        DownloadTicketVO result = downloadService.createDownloadRecord(100L, "127.0.0.1", "JUnit");

        assertThat(result.counted()).isTrue();
        verify(rankingService).recordResourceDownload(100L);
    }

    @Test
    void repeatedDownloadShouldNotRecordHeatAgain() {
        when(valueOperations.setIfAbsent(
                RedisKeyConstants.downloadDedup(10001L, 100L), "1", 600L, TimeUnit.SECONDS)).thenReturn(false);

        DownloadTicketVO result = downloadService.createDownloadRecord(100L, "127.0.0.1", "JUnit");

        assertThat(result.counted()).isFalse();
        verify(rankingService, never()).recordResourceDownload(100L);
    }

    @Test
    void rankingFailureShouldNotFailSuccessfulDownload() {
        when(valueOperations.setIfAbsent(
                RedisKeyConstants.downloadDedup(10001L, 100L), "1", 600L, TimeUnit.SECONDS)).thenReturn(true);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.increment(RedisKeyConstants.DOWNLOAD_DELTA, "100", 1)).thenReturn(1L);
        org.mockito.Mockito.doThrow(new RuntimeException("redis unavailable"))
                .when(rankingService).recordResourceDownload(100L);

        DownloadTicketVO result = downloadService.createDownloadRecord(100L, "127.0.0.1", "JUnit");

        assertThat(result.counted()).isTrue();
        verify(downloadRecordMapper).insert(any());
    }

    private Resource approvedResource() {
        Resource resource = new Resource();
        resource.setId(100L);
        resource.setFileId(200L);
        resource.setStatus(Resource.STATUS_APPROVED);
        return resource;
    }

    private FileInfo normalFile() {
        FileInfo fileInfo = new FileInfo();
        fileInfo.setId(200L);
        fileInfo.setStatus(FileInfo.STATUS_NORMAL);
        return fileInfo;
    }
}
