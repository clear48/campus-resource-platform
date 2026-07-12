package com.john.campus.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.john.campus.common.RedisKeyConstants;
import com.john.campus.entity.Resource;
import com.john.campus.enums.RankingPeriod;
import com.john.campus.mapper.ResourceMapper;
import com.john.campus.service.impl.HotRankingMaintenanceServiceImpl;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RReadWriteLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

/**
 * 总榜维护测试：验证重建只处理 APPROVED 资料、临时榜原子替换、快照分批落库和 Redisson 锁竞争降级。
 */
@ExtendWith(MockitoExtension.class)
class HotRankingMaintenanceServiceImplTest {

    private static final String ALL_KEY = RedisKeyConstants.resourceHotRank(RankingPeriod.ALL.getCode());
    private static final String REBUILD_KEY = RedisKeyConstants.resourceHotRankRebuild("active");

    @Mock
    private ResourceMapper resourceMapper;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RReadWriteLock readWriteLock;
    @Mock
    private RLock writeLock;
    @Mock
    private RLock readLock;
    @Mock
    private ZSetOperations<String, String> zSetOperations;
    @Mock
    private HotScoreSnapshotPersistenceService hotScoreSnapshotPersistenceService;

    private HotRankingMaintenanceService hotRankingMaintenanceService;

    @BeforeEach
    void setUp() {
        hotRankingMaintenanceService = new HotRankingMaintenanceServiceImpl(
                resourceMapper,
                stringRedisTemplate,
                redissonClient,
                hotScoreSnapshotPersistenceService,
                2);
        when(redissonClient.getReadWriteLock(RedisKeyConstants.HOT_RANK_MAINTENANCE_LOCK)).thenReturn(readWriteLock);
        org.mockito.Mockito.lenient().when(readWriteLock.writeLock()).thenReturn(writeLock);
        org.mockito.Mockito.lenient().when(readWriteLock.readLock()).thenReturn(readLock);
        // 锁竞争和“all 榜已存在”分支会提前返回，不会访问 ZSet，因此公共桩设为宽松。
        org.mockito.Mockito.lenient().when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
    }

    @Test
    void shouldRebuildMissingAllRankingWithApprovedStatisticsAndAtomicallyReplaceIt() {
        lockAcquired();
        when(stringRedisTemplate.hasKey(ALL_KEY)).thenReturn(false);
        Resource first = approvedResource(101L, 2L, 3L, 4L);
        Resource second = approvedResource(102L, 1L, 0L, 5L);
        when(resourceMapper.selectApprovedResourcesAfterId(0L, 2)).thenReturn(List.of(first, second));
        when(resourceMapper.selectApprovedResourcesAfterId(102L, 2)).thenReturn(List.of());

        hotRankingMaintenanceService.rebuildAllHotRankingIfMissing();

        verify(stringRedisTemplate).delete(REBUILD_KEY);
        ArgumentCaptor<Set<ZSetOperations.TypedTuple<String>>> tuplesCaptor = ArgumentCaptor.forClass(Set.class);
        verify(zSetOperations).add(eq(REBUILD_KEY), tuplesCaptor.capture());
        assertThat(tuplesCaptor.getValue())
                .extracting(tuple -> Map.entry(tuple.getValue(), tuple.getScore()))
                .containsExactlyInAnyOrder(Map.entry("101", 23D), Map.entry("102", 10D));
        verify(stringRedisTemplate).rename(REBUILD_KEY, ALL_KEY);
        verify(writeLock).unlock();
    }

    @Test
    void shouldNotRebuildWhenAllRankingAlreadyExists() {
        lockAcquired();
        when(stringRedisTemplate.hasKey(ALL_KEY)).thenReturn(true);

        hotRankingMaintenanceService.rebuildAllHotRankingIfMissing();

        verify(resourceMapper, never()).selectApprovedResourcesAfterId(anyLong(), any(Integer.class));
        verify(stringRedisTemplate, never()).delete(REBUILD_KEY);
    }

    @Test
    void shouldSnapshotOnlyValidAllRankingMembersInBatches() {
        readLockAcquired();
        Set<ZSetOperations.TypedTuple<String>> firstBatch = new LinkedHashSet<>();
        firstBatch.add(new DefaultTypedTuple<>("101", 8D));
        firstBatch.add(new DefaultTypedTuple<>("invalid", Double.NaN));
        when(zSetOperations.rangeWithScores(ALL_KEY, 0L, 1L)).thenReturn(firstBatch);
        when(zSetOperations.rangeWithScores(ALL_KEY, 2L, 3L)).thenReturn(Set.of());

        hotRankingMaintenanceService.snapshotAllHotScores();

        verify(hotScoreSnapshotPersistenceService)
                .persistApprovedHotScores(Map.of(101L, BigDecimal.valueOf(8D)));
    }

    @Test
    void shouldSkipMaintenanceWhenAnotherInstanceOwnsTheLock() {
        when(writeLock.tryLock()).thenReturn(false);

        hotRankingMaintenanceService.rebuildAllHotRankingIfMissing();

        verify(resourceMapper, never()).selectApprovedResourcesAfterId(anyLong(), any(Integer.class));
        verify(stringRedisTemplate, never()).opsForZSet();
    }

    @Test
    void explicitRebuildShouldWaitForWriteLockInsteadOfSilentlySkipping() {
        // 管理员入口调用显式重建；即使其他实例正在运行，也应等待后完成本次重建。
        when(writeLock.isHeldByCurrentThread()).thenReturn(true);
        when(resourceMapper.selectApprovedResourcesAfterId(0L, 2)).thenReturn(List.of());

        hotRankingMaintenanceService.rebuildAllHotRanking();

        verify(writeLock).lock();
        verify(stringRedisTemplate).delete(ALL_KEY);
    }

    private void lockAcquired() {
        // tryLock() 不传 leaseTime，实际运行时会启用 Redisson 看门狗自动续期。
        when(writeLock.tryLock()).thenReturn(true);
        when(writeLock.isHeldByCurrentThread()).thenReturn(true);
    }

    private void readLockAcquired() {
        // 快照与实时热度写入共享读锁，只有重建写锁占用时才会跳过本轮。
        when(readLock.tryLock()).thenReturn(true);
        when(readLock.isHeldByCurrentThread()).thenReturn(true);
    }

    private Resource approvedResource(Long id, Long downloads, Long favorites, Long views) {
        Resource resource = new Resource();
        resource.setId(id);
        resource.setStatus(Resource.STATUS_APPROVED);
        resource.setDownloadCount(downloads);
        resource.setFavoriteCount(favorites);
        resource.setViewCount(views);
        return resource;
    }
}
