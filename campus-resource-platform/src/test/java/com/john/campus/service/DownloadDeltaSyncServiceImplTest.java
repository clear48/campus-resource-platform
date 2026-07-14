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
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * 下载增量同步编排测试：覆盖锁竞争、原子批次隔离、失败保留和单批上限，确保并发下载不会因清理 Hash 丢失。
 */
@ExtendWith(MockitoExtension.class)
class DownloadDeltaSyncServiceImplTest {

    private static final String BATCH_ID = "5f0e4d2e-7d33-4ab3-ae06-9c2afcc9d202";
    private static final String SYNCING_KEY = RedisKeyConstants.downloadDeltaSyncing(BATCH_ID);

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;
    @Mock
    private DownloadDeltaPersistenceService downloadDeltaPersistenceService;

    private DownloadDeltaSyncService syncService;

    @BeforeEach
    void setUp() {
        syncService = new DownloadDeltaSyncServiceImpl(
                stringRedisTemplate, redissonClient, downloadDeltaPersistenceService, 2);
        when(redissonClient.getLock(RedisKeyConstants.DOWNLOAD_DELTA_SYNC_LOCK)).thenReturn(lock);
        org.mockito.Mockito.lenient().when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        org.mockito.Mockito.lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void shouldAtomicallyMoveCurrentDeltaThenPersistAndConfirmFields() {
        lockAcquired();
        when(valueOperations.get(RedisKeyConstants.DOWNLOAD_DELTA_SYNCING_CURRENT)).thenReturn(null);
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString())).thenReturn(1L);
        Map<Object, Object> batch = new LinkedHashMap<>();
        batch.put("101", "5");
        batch.put("102", "3");
        when(hashOperations.entries(anyString())).thenReturn(batch);

        syncService.syncDownloadDeltas();

        verify(lock).tryLock();
        verify(lock).unlock();
        ArgumentCaptor<DefaultRedisScript> scriptCaptor = ArgumentCaptor.forClass(DefaultRedisScript.class);
        verify(stringRedisTemplate, org.mockito.Mockito.times(2))
                .execute(scriptCaptor.capture(), anyList(), anyString());
        assertThat(scriptCaptor.getAllValues()).anySatisfy(script ->
                assertThat(script.getScriptAsString()).contains("RENAME", "SET"));
        assertThat(scriptCaptor.getAllValues()).anySatisfy(script ->
                assertThat(script.getScriptAsString()).contains("GET", "ARGV[1]"));
        ArgumentCaptor<Map<Long, Long>> deltasCaptor = ArgumentCaptor.forClass(Map.class);
        verify(downloadDeltaPersistenceService).persistDownloadDeltas(anyString(), deltasCaptor.capture());
        assertThat(deltasCaptor.getValue()).containsExactlyInAnyOrderEntriesOf(Map.of(101L, 5L, 102L, 3L));
        verify(hashOperations).delete(anyString(), eq("101"), eq("102"));
        verify(downloadDeltaPersistenceService).markDownloadDeltasConfirmed(anyString(), eq(java.util.List.of(101L, 102L)));
    }

    @Test
    void shouldResumeExistingSyncingBatchBeforeReadingNewDelta() {
        lockAcquired();
        when(valueOperations.get(RedisKeyConstants.DOWNLOAD_DELTA_SYNCING_CURRENT)).thenReturn(BATCH_ID);
        when(stringRedisTemplate.hasKey(SYNCING_KEY)).thenReturn(true);
        when(hashOperations.entries(SYNCING_KEY)).thenReturn(Map.of("101", "5"));

        syncService.syncDownloadDeltas();

        verify(stringRedisTemplate, never()).execute(any(DefaultRedisScript.class), anyList(), anyString());
        verify(downloadDeltaPersistenceService).persistDownloadDeltas(BATCH_ID, Map.of(101L, 5L));
    }

    @Test
    void shouldKeepSyncingBatchWhenDatabasePersistenceFails() {
        lockAcquired();
        when(valueOperations.get(RedisKeyConstants.DOWNLOAD_DELTA_SYNCING_CURRENT)).thenReturn(BATCH_ID);
        when(stringRedisTemplate.hasKey(SYNCING_KEY)).thenReturn(true);
        when(hashOperations.entries(SYNCING_KEY)).thenReturn(Map.of("101", "5"));
        org.mockito.Mockito.doThrow(new RuntimeException("database unavailable"))
                .when(downloadDeltaPersistenceService).persistDownloadDeltas(BATCH_ID, Map.of(101L, 5L));

        syncService.syncDownloadDeltas();

        // 失败批次不执行 HDEL，下一轮会优先恢复该批次而不是丢弃下载增量。
        verify(hashOperations, never()).delete(eq(SYNCING_KEY), any());
    }

    @Test
    void shouldSkipSynchronizationWhenAnotherInstanceOwnsLock() {
        when(lock.tryLock()).thenReturn(false);

        syncService.syncDownloadDeltas();

        verify(stringRedisTemplate, never()).hasKey(anyString());
        verify(downloadDeltaPersistenceService, never()).persistDownloadDeltas(
                anyString(), org.mockito.ArgumentMatchers.<Map<Long, Long>>any());
    }

    @Test
    void shouldLimitSinglePersistenceBatchAndLeaveRemainingFieldsForNextRun() {
        lockAcquired();
        when(valueOperations.get(RedisKeyConstants.DOWNLOAD_DELTA_SYNCING_CURRENT)).thenReturn(BATCH_ID);
        when(stringRedisTemplate.hasKey(SYNCING_KEY)).thenReturn(true);
        Map<Object, Object> batch = new LinkedHashMap<>();
        batch.put("101", "5");
        batch.put("102", "3");
        batch.put("103", "2");
        when(hashOperations.entries(SYNCING_KEY)).thenReturn(batch);

        syncService.syncDownloadDeltas();

        ArgumentCaptor<Map<Long, Long>> deltasCaptor = ArgumentCaptor.forClass(Map.class);
        verify(downloadDeltaPersistenceService).persistDownloadDeltas(eq(BATCH_ID), deltasCaptor.capture());
        assertThat(deltasCaptor.getValue()).containsExactlyInAnyOrderEntriesOf(Map.of(101L, 5L, 102L, 3L));
        verify(hashOperations).delete(SYNCING_KEY, "101", "102");
    }

    @Test
    void shouldReuseSameBatchIdAfterRedisConfirmationFails() {
        lockAcquired();
        when(valueOperations.get(RedisKeyConstants.DOWNLOAD_DELTA_SYNCING_CURRENT)).thenReturn(BATCH_ID);
        when(stringRedisTemplate.hasKey(SYNCING_KEY)).thenReturn(true);
        when(hashOperations.entries(SYNCING_KEY)).thenReturn(Map.of("101", "5"));
        org.mockito.Mockito.doThrow(new RuntimeException("redis hdel timeout"))
                .doReturn(1L)
                .when(hashOperations).delete(SYNCING_KEY, "101");

        syncService.syncDownloadDeltas();
        syncService.syncDownloadDeltas();

        // 同一 UUID 传给持久化层；真实 MySQL 唯一键会让第二次只做 Redis 确认而不会重复累加。
        verify(downloadDeltaPersistenceService, org.mockito.Mockito.times(2))
                .persistDownloadDeltas(BATCH_ID, Map.of(101L, 5L));
        verify(downloadDeltaPersistenceService).markDownloadDeltasConfirmed(BATCH_ID, java.util.List.of(101L));
    }

    @Test
    void shouldKeepLegacyActiveHashForManualMigrationInsteadOfRiskingDuplicateIncrement() {
        String legacyKey = RedisKeyConstants.downloadDeltaSyncing("active");
        lockAcquired();
        when(valueOperations.get(RedisKeyConstants.DOWNLOAD_DELTA_SYNCING_CURRENT)).thenReturn(null);
        when(stringRedisTemplate.hasKey(legacyKey)).thenReturn(true);

        syncService.syncDownloadDeltas();

        // 旧版本没有幂等记录，无法识别“已提交但未 HDEL”的历史批次；必须保留给人工核对而非冒险重复累计。
        verify(downloadDeltaPersistenceService, never()).persistDownloadDeltas(anyString(), any());
        verify(stringRedisTemplate, never()).execute(any(DefaultRedisScript.class), anyList(), anyString());
    }

    @Test
    void shouldUseCompareAndDeleteScriptForStaleCurrentPointer() {
        lockAcquired();
        when(valueOperations.get(RedisKeyConstants.DOWNLOAD_DELTA_SYNCING_CURRENT)).thenReturn(BATCH_ID);
        when(stringRedisTemplate.hasKey(SYNCING_KEY)).thenReturn(false);
        when(stringRedisTemplate.hasKey(RedisKeyConstants.downloadDeltaSyncing("active"))).thenReturn(false);
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString())).thenReturn(0L);

        syncService.syncDownloadDeltas();

        // 旧 worker 只能通过 GET == batchId 的 Lua CAS 清理指针，不能直接 DEL 而误删新 worker 的批次指针。
        verify(stringRedisTemplate, never()).delete(RedisKeyConstants.DOWNLOAD_DELTA_SYNCING_CURRENT);
        ArgumentCaptor<DefaultRedisScript> scriptCaptor = ArgumentCaptor.forClass(DefaultRedisScript.class);
        verify(stringRedisTemplate, org.mockito.Mockito.atLeastOnce())
                .execute(scriptCaptor.capture(), anyList(), anyString());
        assertThat(scriptCaptor.getAllValues()).anySatisfy(script ->
                assertThat(script.getScriptAsString()).contains("GET", "ARGV[1]"));
    }

    private void lockAcquired() {
        // tryLock() 不传 leaseTime，实际运行时会启用 Redisson 看门狗自动续期。
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
    }
}
