package com.john.campus.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.john.campus.common.ErrorCode;
import com.john.campus.common.RedisKeyConstants;
import com.john.campus.dto.HotResourceRankingQueryDTO;
import com.john.campus.dto.HotSearchKeywordRankingQueryDTO;
import com.john.campus.entity.Resource;
import com.john.campus.enums.RankingPeriod;
import com.john.campus.exception.BusinessException;
import com.john.campus.mapper.ResourceMapper;
import com.john.campus.service.impl.RankingServiceImpl;
import com.john.campus.vo.HotResourceRankingVO;
import com.john.campus.vo.HotSearchKeywordRankingVO;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RReadWriteLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

/**
 * 排行榜 Service 单元测试：通过 mock Redis 与 Mapper 验证参数、顺序恢复、脏数据过滤和降级策略。
 */
@ExtendWith(MockitoExtension.class)
class RankingServiceImplTest {

    @Mock
    private ResourceMapper resourceMapper;

    @Mock
    private ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RReadWriteLock allHotRankReadWriteLock;

    @Mock
    private RLock allHotRankReadLock;

    private RankingService rankingService;

    @BeforeEach
    void setUp() {
        rankingService = new RankingServiceImpl(resourceMapper, stringRedisTemplateProvider, redissonClient);
        org.mockito.Mockito.lenient().when(redissonClient.getReadWriteLock(RedisKeyConstants.HOT_RANK_MAINTENANCE_LOCK))
                .thenReturn(allHotRankReadWriteLock);
        org.mockito.Mockito.lenient().when(allHotRankReadWriteLock.readLock()).thenReturn(allHotRankReadLock);
        org.mockito.Mockito.lenient().when(allHotRankReadLock.isHeldByCurrentThread()).thenReturn(true);
    }

    @Test
    void hotResourcesShouldRestoreRedisOrderAndFilterStaleCandidates() {
        mockRedisAvailable();
        HotResourceRankingQueryDTO query = new HotResourceRankingQueryDTO();
        query.setLimit(2);
        query.setCategoryId(10L);
        query.setPeriod("daily");
        when(zSetOperations.reverseRangeWithScores(
                RedisKeyConstants.resourceHotRank("daily"), 0, 19))
                .thenReturn(tupleSet(
                        tuple("invalid", 100.0D),
                        tuple("102", 90.0D),
                        tuple("101", 80.0D),
                        tuple("100", 70.0D)));
        when(resourceMapper.selectApprovedRankingCandidatesByIds(List.of(102L, 101L, 100L), 10L))
                .thenReturn(List.of(
                        resource(100L, 10L, Resource.STATUS_APPROVED, "较低分资料", "70.00"),
                        resource(101L, 10L, Resource.STATUS_APPROVED, "较高分资料", "80.00")));

        List<HotResourceRankingVO> result = rankingService.listHotResources(query);

        assertThat(result).extracting(HotResourceRankingVO::resourceId).containsExactly(101L, 100L);
        assertThat(result).extracting(HotResourceRankingVO::rank).containsExactly(1, 2);
        assertThat(result).extracting(HotResourceRankingVO::hotScore)
                .extracting(BigDecimal::doubleValue)
                .containsExactly(80.0D, 70.0D);
    }

    @Test
    void hotResourcesShouldContinueScanningWhenFirstCandidateSegmentIsStale() {
        mockRedisAvailable();
        HotResourceRankingQueryDTO query = new HotResourceRankingQueryDTO();
        query.setLimit(1);
        LinkedHashSet<ZSetOperations.TypedTuple<String>> staleCandidates = new LinkedHashSet<>();
        for (long resourceId = 1000L; resourceId < 1020L; resourceId++) {
            staleCandidates.add(tuple(String.valueOf(resourceId), 1.0D));
        }
        when(zSetOperations.reverseRangeWithScores(
                RedisKeyConstants.resourceHotRank("weekly"), 0, 19)).thenReturn(staleCandidates);
        when(zSetOperations.reverseRangeWithScores(
                RedisKeyConstants.resourceHotRank("weekly"), 20, 39))
                .thenReturn(tupleSet(tuple("100", 10.0D)));
        when(resourceMapper.selectApprovedRankingCandidatesByIds(anyList(), isNull()))
                .thenReturn(List.of())
                .thenReturn(List.of(resource(100L, 1L, Resource.STATUS_APPROVED, "补足资料", "10.00")));

        List<HotResourceRankingVO> result = rankingService.listHotResources(query);

        assertThat(result).extracting(HotResourceRankingVO::resourceId).containsExactly(100L);
        verify(zSetOperations).reverseRangeWithScores(RedisKeyConstants.resourceHotRank("weekly"), 20, 39);
    }

    @Test
    void hotResourcesShouldFallbackToMysqlWhenRedisIsUnavailable() {
        HotResourceRankingQueryDTO query = new HotResourceRankingQueryDTO();
        query.setLimit(2);
        query.setCategoryId(8L);
        when(stringRedisTemplateProvider.getIfAvailable()).thenReturn(null);
        when(resourceMapper.selectHotApprovedResources(8L, 2)).thenReturn(List.of(
                resource(200L, 8L, Resource.STATUS_APPROVED, "热度第一", "99.00"),
                resource(201L, 8L, Resource.STATUS_APPROVED, "热度第二", "88.00")));

        List<HotResourceRankingVO> result = rankingService.listHotResources(query);

        assertThat(result).extracting(HotResourceRankingVO::resourceId).containsExactly(200L, 201L);
        assertThat(result).extracting(HotResourceRankingVO::rank).containsExactly(1, 2);
    }

    @Test
    void hotResourceQueryShouldRejectInvalidParametersBeforeReadingRedis() {
        HotResourceRankingQueryDTO invalidLimitQuery = new HotResourceRankingQueryDTO();
        invalidLimitQuery.setLimit(51);
        HotResourceRankingQueryDTO invalidPeriodQuery = new HotResourceRankingQueryDTO();
        invalidPeriodQuery.setPeriod("yearly");

        assertThatThrownBy(() -> rankingService.listHotResources(invalidLimitQuery))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.PARAM_ERROR.getCode());
        assertThatThrownBy(() -> rankingService.listHotResources(invalidPeriodQuery))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.PARAM_ERROR.getCode());
    }

    @Test
    void hotSearchKeywordsShouldMapRedisScoresAndRejectAllPeriod() {
        mockRedisAvailable();
        HotSearchKeywordRankingQueryDTO query = new HotSearchKeywordRankingQueryDTO();
        query.setLimit(3);
        query.setPeriod("daily");
        when(zSetOperations.reverseRangeWithScores(
                RedisKeyConstants.searchKeywordRank("daily"), 0, 2))
                .thenReturn(tupleSet(tuple("数据结构", 25.0D), tuple("   ", 10.0D), tuple("操作系统", 12.0D)));

        List<HotSearchKeywordRankingVO> result = rankingService.listHotSearchKeywords(query);

        assertThat(result).extracting(HotSearchKeywordRankingVO::keyword).containsExactly("数据结构", "操作系统");
        assertThat(result).extracting(HotSearchKeywordRankingVO::searchCount).containsExactly(25L, 12L);
        assertThat(result).extracting(HotSearchKeywordRankingVO::rank).containsExactly(1, 2);

        HotSearchKeywordRankingQueryDTO allPeriodQuery = new HotSearchKeywordRankingQueryDTO();
        allPeriodQuery.setPeriod("all");
        assertThatThrownBy(() -> rankingService.listHotSearchKeywords(allPeriodQuery))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.PARAM_ERROR.getCode());
    }

    @Test
    void hotSearchKeywordsShouldReturnEmptyWhenRedisFails() {
        mockRedisAvailable();
        when(zSetOperations.reverseRangeWithScores(
                RedisKeyConstants.searchKeywordRank("daily"), 0, 9))
                .thenThrow(new RuntimeException("redis unavailable"));

        assertThat(rankingService.listHotSearchKeywords(new HotSearchKeywordRankingQueryDTO())).isEmpty();
    }

    @Test
    void resourceHeatEventsShouldUpdateAllPeriodsAndKeepAllRankingWithoutTtl() {
        mockRedisAvailable();

        rankingService.recordResourceDownload(100L);
        rankingService.recordResourceFavorite(100L);
        rankingService.recordResourceUnfavorite(100L);

        for (RankingPeriod period : RankingPeriod.values()) {
            String key = RedisKeyConstants.resourceHotRank(period.getCode());
            verify(zSetOperations).incrementScore(key, "100", 5D);
            verify(zSetOperations).incrementScore(key, "100", 3D);
            verify(zSetOperations).incrementScore(key, "100", -3D);
            // 三个行为事件都刷新周期榜 TTL；all 总榜没有 TTL，因此不会调用 expire。
            period.getTtl().ifPresent(ttl -> verify(stringRedisTemplate, times(3)).expire(key, ttl));
        }
    }

    @Test
    void allRankingHeatWriteShouldAcquireSharedReadLockBeforeIncrement() {
        mockRedisAvailable();

        rankingService.recordResourceDownload(100L);

        // 与重建写锁共享同一个读写锁，确保 all 榜的 ZINCRBY 只能发生在 RENAME 前或 RENAME 后，绝不会被替换丢失。
        InOrder inOrder = org.mockito.Mockito.inOrder(allHotRankReadLock, zSetOperations);
        inOrder.verify(allHotRankReadLock).lock();
        inOrder.verify(zSetOperations).incrementScore(
                RedisKeyConstants.resourceHotRank(RankingPeriod.ALL.getCode()), "100", 5D);
        verify(allHotRankReadLock).unlock();
    }

    @Test
    void approveAndOfflineShouldInitializeThenRemoveAllRankingMembers() {
        mockRedisAvailable();

        rankingService.initializeApprovedResource(100L);
        rankingService.removeOfflineResource(100L);

        for (RankingPeriod period : RankingPeriod.values()) {
            String key = RedisKeyConstants.resourceHotRank(period.getCode());
            // ZINCRBY 0 会保留旧分数，并在成员缺失时创建初始成员。
            verify(zSetOperations).incrementScore(key, "100", 0D);
            verify(zSetOperations).remove(key, "100");
        }
    }

    @Test
    void resourceHeatUpdateShouldNotPropagateRedisFailure() {
        mockRedisAvailable();
        when(zSetOperations.incrementScore(RedisKeyConstants.resourceHotRank("daily"), "100", 5D))
                .thenThrow(new RuntimeException("redis unavailable"));

        rankingService.recordResourceDownload(100L);

        // Redis 是派生数据存储，异常必须被排行榜服务吞掉，调用下载/收藏/审核主流程不感知失败。
        verify(zSetOperations).incrementScore(RedisKeyConstants.resourceHotRank("daily"), "100", 5D);
    }

    private void mockRedisAvailable() {
        when(stringRedisTemplateProvider.getIfAvailable()).thenReturn(stringRedisTemplate);
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
    }

    @SafeVarargs
    private final Set<ZSetOperations.TypedTuple<String>> tupleSet(ZSetOperations.TypedTuple<String>... tuples) {
        LinkedHashSet<ZSetOperations.TypedTuple<String>> orderedTuples = new LinkedHashSet<>();
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            orderedTuples.add(tuple);
        }
        return orderedTuples;
    }

    private ZSetOperations.TypedTuple<String> tuple(String value, double score) {
        return new DefaultTypedTuple<>(value, score);
    }

    private Resource resource(long id, long categoryId, int status, String title, String hotScore) {
        Resource resource = new Resource();
        resource.setId(id);
        resource.setCategoryId(categoryId);
        resource.setStatus(status);
        resource.setTitle(title);
        resource.setCourseName("测试课程");
        resource.setDownloadCount(100L);
        resource.setFavoriteCount(20L);
        resource.setHotScore(new BigDecimal(hotScore));
        return resource;
    }
}
