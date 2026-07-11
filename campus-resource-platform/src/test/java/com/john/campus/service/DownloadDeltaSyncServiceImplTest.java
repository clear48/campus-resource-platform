package com.john.campus.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.john.campus.common.RedisKeyConstants;
import com.john.campus.service.impl.DownloadDeltaSyncServiceImpl;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 下载增量同步编排测试：覆盖锁竞争、原子批次隔离、失败保留和单批上限，确保并发下载不会因清理 Hash 丢失。
 */
@ExtendWith(MockitoExtension.class)
class DownloadDeltaSyncServiceImplTest {

    private static final String SYNCING_KEY = RedisKeyConstants.downloadDeltaSyncing("active");

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;
    @Mock
    private DownloadDeltaPersistenceService downloadDeltaPersistenceService;

    private DownloadDeltaSyncService syncService;

    @BeforeEach
    void setUp() {
        syncService = new DownloadDeltaSyncServiceImpl(
                stringRedisTemplate, downloadDeltaPersistenceService, 2, 120L);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        org.mockito.Mockito.lenient().when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        // 每轮都会执行安全释放脚本；其结果不影响同步断言。
        org.mockito.Mockito.lenient().doReturn(1L).when(stringRedisTemplate)
                .execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    void shouldAtomicallyMoveCurrentDeltaThenPersistAndConfirmFields() {
        lockAcquired();
        when(stringRedisTemplate.hasKey(SYNCING_KEY)).thenReturn(false);
        when(stringRedisTemplate.hasKey(RedisKeyConstants.DOWNLOAD_DELTA)).thenReturn(true);
        Map<Object, Object> batch = new LinkedHashMap<>();
        batch.put("101", "5");
        batch.put("102", "3");
        when(hashOperations.entries(SYNCING_KEY)).thenReturn(batch);

        syncService.syncDownloadDeltas();

        verify(stringRedisTemplate).rename(RedisKeyConstants.DOWNLOAD_DELTA, SYNCING_KEY);
        ArgumentCaptor<Map<Long, Long>> deltasCaptor = ArgumentCaptor.forClass(Map.class);
        verify(downloadDeltaPersistenceService).persistDownloadDeltas(deltasCaptor.capture());
        assertThat(deltasCaptor.getValue()).containsExactlyInAnyOrderEntriesOf(Map.of(101L, 5L, 102L, 3L));
        verify(hashOperations).delete(SYNCING_KEY, "101", "102");
    }

    @Test
    void shouldResumeExistingSyncingBatchBeforeReadingNewDelta() {
        lockAcquired();
        when(stringRedisTemplate.hasKey(SYNCING_KEY)).thenReturn(true);
        when(hashOperations.entries(SYNCING_KEY)).thenReturn(Map.of("101", "5"));

        syncService.syncDownloadDeltas();

        verify(stringRedisTemplate, never()).rename(anyString(), anyString());
        verify(downloadDeltaPersistenceService).persistDownloadDeltas(Map.of(101L, 5L));
    }

    @Test
    void shouldKeepSyncingBatchWhenDatabasePersistenceFails() {
        lockAcquired();
        when(stringRedisTemplate.hasKey(SYNCING_KEY)).thenReturn(true);
        when(hashOperations.entries(SYNCING_KEY)).thenReturn(Map.of("101", "5"));
        org.mockito.Mockito.doThrow(new RuntimeException("database unavailable"))
                .when(downloadDeltaPersistenceService).persistDownloadDeltas(Map.of(101L, 5L));

        syncService.syncDownloadDeltas();

        // 失败批次不执行 HDEL，下一轮会优先恢复该批次而不是丢弃下载增量。
        verify(hashOperations, never()).delete(eq(SYNCING_KEY), any());
    }

    @Test
    void shouldSkipSynchronizationWhenAnotherInstanceOwnsLock() {
        when(valueOperations.setIfAbsent(
                eq(RedisKeyConstants.DOWNLOAD_DELTA_SYNC_LOCK), anyString(), eq(Duration.ofSeconds(120))))
                .thenReturn(false);

        syncService.syncDownloadDeltas();

        verify(stringRedisTemplate, never()).hasKey(anyString());
        verify(downloadDeltaPersistenceService, never()).persistDownloadDeltas(any());
    }

    @Test
    void shouldLimitSinglePersistenceBatchAndLeaveRemainingFieldsForNextRun() {
        lockAcquired();
        when(stringRedisTemplate.hasKey(SYNCING_KEY)).thenReturn(true);
        Map<Object, Object> batch = new LinkedHashMap<>();
        batch.put("101", "5");
        batch.put("102", "3");
        batch.put("103", "2");
        when(hashOperations.entries(SYNCING_KEY)).thenReturn(batch);

        syncService.syncDownloadDeltas();

        ArgumentCaptor<Map<Long, Long>> deltasCaptor = ArgumentCaptor.forClass(Map.class);
        verify(downloadDeltaPersistenceService).persistDownloadDeltas(deltasCaptor.capture());
        assertThat(deltasCaptor.getValue()).containsExactlyInAnyOrderEntriesOf(Map.of(101L, 5L, 102L, 3L));
        verify(hashOperations).delete(SYNCING_KEY, "101", "102");
    }

    private void lockAcquired() {
        when(valueOperations.setIfAbsent(
                eq(RedisKeyConstants.DOWNLOAD_DELTA_SYNC_LOCK), anyString(), eq(Duration.ofSeconds(120))))
                .thenReturn(true);
    }
}
