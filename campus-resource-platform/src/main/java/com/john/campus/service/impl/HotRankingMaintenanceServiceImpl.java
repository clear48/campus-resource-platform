package com.john.campus.service.impl;

import com.john.campus.common.RedisKeyConstants;
import com.john.campus.entity.Resource;
import com.john.campus.enums.RankingPeriod;
import com.john.campus.mapper.ResourceMapper;
import com.john.campus.service.HotRankingMaintenanceService;
import com.john.campus.service.HotScoreSnapshotPersistenceService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.stereotype.Service;

/**
 * all 总榜维护实现：重建时以 MySQL 统计字段为准，快照时以 Redis all 榜为准；两者通过同一看门狗锁避免多实例重叠执行。
 */
@Service
public class HotRankingMaintenanceServiceImpl implements HotRankingMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(HotRankingMaintenanceServiceImpl.class);

    /** 固定临时批次名配合维护锁使用；未完成重建不会覆盖线上正式 all 榜。 */
    private static final String ACTIVE_REBUILD_BATCH_ID = "active";
    /** 首版热度公式中的统计权重，与下载、收藏实时行为权重保持一致。 */
    private static final BigDecimal DOWNLOAD_WEIGHT = BigDecimal.valueOf(5L);
    private static final BigDecimal FAVORITE_WEIGHT = BigDecimal.valueOf(3L);
    private static final BigDecimal VIEW_WEIGHT = BigDecimal.ONE;

    /** 提供 APPROVED 游标扫描与热度快照更新 SQL。 */
    private final ResourceMapper resourceMapper;
    /** Redis 负责 all 榜构建、原子替换和分数读取。 */
    private final StringRedisTemplate stringRedisTemplate;
    /** Redisson 锁客户端延迟创建，获取锁时不传 leaseTime 以启用看门狗。 */
    private final RedissonClient redissonClient;
    /** 独立事务 Bean，每个快照批次通过 Spring 代理提交或回滚。 */
    private final HotScoreSnapshotPersistenceService hotScoreSnapshotPersistenceService;
    /** 单个 Redis 写入或 MySQL 事务的最大资料数量，避免后台任务一次占用过多内存和事务时间。 */
    private final int batchSize;

    public HotRankingMaintenanceServiceImpl(
            ResourceMapper resourceMapper,
            StringRedisTemplate stringRedisTemplate,
            @Lazy RedissonClient redissonClient,
            HotScoreSnapshotPersistenceService hotScoreSnapshotPersistenceService,
            @Value("${rank.sync.hot-ranking.batch-size:500}") int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("总榜维护批次大小必须大于 0");
        }
        this.resourceMapper = resourceMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.redissonClient = redissonClient;
        this.hotScoreSnapshotPersistenceService = hotScoreSnapshotPersistenceService;
        this.batchSize = batchSize;
    }

    /** 仅在 all Key 不存在时重建，避免周期性任务反复用 MySQL 快照覆盖正常的 Redis 实时增量。 */
    @Override
    public void rebuildAllHotRankingIfMissing() {
        withMaintenanceLock("检查并重建 all 总榜", () -> {
            if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(allHotRankKey()))) {
                rebuildAllHotRankingInternal();
            }
        });
    }

    /** 供后续受权限保护的内部操作显式调用；每次均以当前 APPROVED 资料的统计字段重新计算总榜。 */
    @Override
    public void rebuildAllHotRanking() {
        withMaintenanceLock("重建 all 总榜", this::rebuildAllHotRankingInternal);
    }

    /** 将 Redis all 榜当前分数分批落库；快照失败不影响 Redis 实时榜，下轮任务会从头重新读取。 */
    @Override
    public void snapshotAllHotScores() {
        withMaintenanceLock("回写 all 总榜热度快照", this::snapshotAllHotScoresInternal);
    }

    /**
     * 先构建临时 ZSet，再以 RENAME 一次性替换正式 all 榜。重建失败时旧榜保留，查询不会读到半成品。
     */
    private void rebuildAllHotRankingInternal() {
        String rebuildKey = RedisKeyConstants.resourceHotRankRebuild(ACTIVE_REBUILD_BATCH_ID);
        ZSetOperations<String, String> zSetOperations = stringRedisTemplate.opsForZSet();
        stringRedisTemplate.delete(rebuildKey);

        long lastResourceId = 0L;
        boolean hasApprovedResource = false;
        while (true) {
            List<Resource> resources = resourceMapper.selectApprovedResourcesAfterId(lastResourceId, batchSize);
            if (resources == null || resources.isEmpty()) {
                break;
            }

            Set<TypedTuple<String>> tuples = new LinkedHashSet<>();
            for (Resource resource : resources) {
                if (!isApprovedResource(resource)) {
                    continue;
                }
                lastResourceId = Math.max(lastResourceId, resource.getId());
                tuples.add(new DefaultTypedTuple<>(
                        String.valueOf(resource.getId()), calculateHotScore(resource).doubleValue()));
            }
            if (tuples.isEmpty()) {
                throw new IllegalStateException("总榜重建分页未返回有效资料 ID，无法推进游标");
            }
            zSetOperations.add(rebuildKey, tuples);
            hasApprovedResource = true;
            if (resources.size() < batchSize) {
                break;
            }
        }

        if (hasApprovedResource) {
            // Redis 单命令 RENAME 覆盖正式 Key，外部查询只会看到旧榜或完整新榜。
            stringRedisTemplate.rename(rebuildKey, allHotRankKey());
        } else {
            // 没有公开资料时 all 榜应为空，避免遗留已下架资料继续出现在 Redis 中。
            stringRedisTemplate.delete(allHotRankKey());
        }
    }

    /** 以 ZSet 下标分批读取总榜；顺序不影响快照值，offset 只用于控制单批内存与事务大小。 */
    private void snapshotAllHotScoresInternal() {
        ZSetOperations<String, String> zSetOperations = stringRedisTemplate.opsForZSet();
        long offset = 0L;
        while (true) {
            Set<TypedTuple<String>> tuples = zSetOperations.rangeWithScores(
                    allHotRankKey(), offset, offset + batchSize - 1L);
            if (tuples == null || tuples.isEmpty()) {
                return;
            }

            Map<Long, BigDecimal> hotScores = extractValidHotScores(tuples);
            if (!hotScores.isEmpty()) {
                // 独立 Bean 的事务边界只包围本批 MySQL 写入，异常会回滚当前批并终止本轮快照。
                hotScoreSnapshotPersistenceService.persistApprovedHotScores(hotScores);
            }
            if (tuples.size() < batchSize) {
                return;
            }
            offset += tuples.size();
        }
    }

    /** Redis 外部数据可能包含非法 member 或非有限分数，跳过脏成员不能让全部快照任务失败。 */
    private Map<Long, BigDecimal> extractValidHotScores(Set<TypedTuple<String>> tuples) {
        Map<Long, BigDecimal> hotScores = new LinkedHashMap<>();
        for (TypedTuple<String> tuple : tuples) {
            Long resourceId = parsePositiveResourceId(tuple.getValue());
            Double score = tuple.getScore();
            if (resourceId == null || score == null || !Double.isFinite(score)) {
                log.warn("跳过无法回写的 all 总榜成员: resourceId={}, hotScore={}", tuple.getValue(), score);
                continue;
            }
            hotScores.put(resourceId, BigDecimal.valueOf(score));
        }
        return hotScores;
    }

    /** 首版不引入时间衰减，使用 MySQL 中的累计下载、收藏和浏览统计稳定计算 all 榜基础分。 */
    private BigDecimal calculateHotScore(Resource resource) {
        return positiveCount(resource.getDownloadCount()).multiply(DOWNLOAD_WEIGHT)
                .add(positiveCount(resource.getFavoriteCount()).multiply(FAVORITE_WEIGHT))
                .add(positiveCount(resource.getViewCount()).multiply(VIEW_WEIGHT));
    }

    private BigDecimal positiveCount(Long count) {
        return BigDecimal.valueOf(count == null ? 0L : Math.max(0L, count));
    }

    private boolean isApprovedResource(Resource resource) {
        return resource != null && resource.getId() != null && resource.getId() > 0 && resource.isApproved();
    }

    private Long parsePositiveResourceId(String value) {
        try {
            long resourceId = Long.parseLong(value);
            return resourceId > 0 ? resourceId : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String allHotRankKey() {
        return RedisKeyConstants.resourceHotRank(RankingPeriod.ALL.getCode());
    }

    /** 获取不到锁或 Redis 异常时只记录日志并跳过，不能让定时线程持续抛出异常。 */
    private void withMaintenanceLock(String action, Runnable task) {
        RLock lock = null;
        try {
            lock = redissonClient.getLock(RedisKeyConstants.HOT_RANK_MAINTENANCE_LOCK);
            if (!lock.tryLock()) {
                log.debug("跳过{}，其他实例正在执行", action);
                return;
            }
            task.run();
        } catch (RuntimeException ex) {
            log.warn("{}失败，本轮结束", action, ex);
        } finally {
            releaseLock(lock, action);
        }
    }

    /** Redisson 会校验锁归属；只有当前线程实际持锁时才解锁，避免覆盖主业务或误释放其他实例的锁。 */
    private void releaseLock(RLock lock, String action) {
        if (lock == null) {
            return;
        }
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (RuntimeException ex) {
            log.warn("{}后释放总榜维护锁失败，将由看门狗超时机制兜底", action, ex);
        }
    }
}
