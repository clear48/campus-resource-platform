package com.john.campus.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.john.campus.common.LoginUser;
import com.john.campus.common.ErrorCode;
import com.john.campus.common.RedisKeyConstants;
import com.john.campus.common.UserContextHolder;
import com.john.campus.entity.FileInfo;
import com.john.campus.entity.DownloadRecord;
import com.john.campus.entity.Resource;
import com.john.campus.exception.BusinessException;
import com.john.campus.mapper.DownloadRecordMapper;
import com.john.campus.mapper.FileInfoMapper;
import com.john.campus.mapper.ResourceMapper;
import com.john.campus.service.impl.DownloadServiceImpl;
import com.john.campus.vo.DownloadTicketVO;
import java.io.ByteArrayInputStream;
import java.time.Duration;
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
import org.springframework.data.redis.core.script.DefaultRedisScript;

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
        lenient().when(resourceMapper.selectById(100L)).thenReturn(approvedResource());
        lenient().when(fileInfoMapper.selectNormalById(200L)).thenReturn(normalFile());
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.setIfAbsent(anyString(), eq("1"), eq(Duration.ofSeconds(60))))
                .thenReturn(true);
        // MyBatis-Plus 插入后会回填主键；单元测试显式模拟该行为，保证票据绑定真实记录 ID。
        lenient().doAnswer(invocation -> {
            DownloadRecord record = invocation.getArgument(0);
            record.setId(300L);
            return 1;
        }).when(downloadRecordMapper).insert(any(DownloadRecord.class));
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
        assertThat(result.downloadTicket()).hasSize(43);
        assertThat(result.expireSeconds()).isEqualTo(60L);
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

    @Test
    @SuppressWarnings("unchecked")
    void validTicketShouldBeConsumedBeforeLoadingApprovedResourceFile() {
        String ticket = "A".repeat(43);
        DownloadRecord record = downloadRecord();
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList())).thenReturn(1L);
        when(downloadRecordMapper.selectById(300L)).thenReturn(record);
        FileInfo fileInfo = normalFile();
        fileInfo.setStoragePath("C:/uploads/test.pdf");
        fileInfo.setOriginalName("test.pdf");
        fileInfo.setMimeType("application/pdf");
        when(fileInfoMapper.selectNormalById(200L)).thenReturn(fileInfo);
        when(fileStorageService.loadAsResource("C:/uploads/test.pdf"))
                .thenReturn(new FileStorageService.FileResource(new ByteArrayInputStream(new byte[]{1}), 1));

        DownloadService.DownloadFileInfo result = downloadService.loadFile(300L, ticket);

        assertThat(result.contentLength()).isEqualTo(1L);
        verify(fileStorageService).loadAsResource("C:/uploads/test.pdf");
    }

    @Test
    @SuppressWarnings("unchecked")
    void reusedTicketShouldBeRejectedBeforeDatabaseAccess() {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList())).thenReturn(0L);

        assertThatThrownBy(() -> downloadService.loadFile(300L, "A".repeat(43)))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.RESOURCE_STATUS_INVALID.getCode());

        verify(downloadRecordMapper, never()).selectById(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void ticketIssuedBeforeOfflineShouldNotLoadFile() {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList())).thenReturn(1L);
        when(downloadRecordMapper.selectById(300L)).thenReturn(downloadRecord());
        Resource offlineResource = approvedResource();
        offlineResource.setStatus(Resource.STATUS_OFFLINE);
        when(resourceMapper.selectById(100L)).thenReturn(offlineResource);

        assertThatThrownBy(() -> downloadService.loadFile(300L, "A".repeat(43)))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.RESOURCE_STATUS_INVALID.getCode());

        verify(fileStorageService, never()).loadAsResource(anyString());
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

    private DownloadRecord downloadRecord() {
        DownloadRecord record = new DownloadRecord();
        record.setId(300L);
        record.setUserId(10001L);
        record.setResourceId(100L);
        record.setFileId(200L);
        record.setDownloadStatus(DownloadRecord.STATUS_SUCCESS);
        return record;
    }
}
