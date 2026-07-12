package com.john.campus.service.impl;

import com.john.campus.common.ErrorCode;
import com.john.campus.common.RedisKeyConstants;
import com.john.campus.dto.HotResourceRankingQueryDTO;
import com.john.campus.dto.HotSearchKeywordRankingQueryDTO;
import com.john.campus.entity.Resource;
import com.john.campus.enums.RankingPeriod;
import com.john.campus.exception.BusinessException;
import com.john.campus.mapper.ResourceMapper;
import com.john.campus.service.RankingService;
import com.john.campus.vo.HotResourceRankingVO;
import com.john.campus.vo.HotSearchKeywordRankingVO;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.redisson.api.RLock;
import org.redisson.api.RReadWriteLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 排行榜查询实现：Redis ZSet 提供实时排序，MySQL 负责补齐公开资料字段和故障降级。
 */
@Service
public class RankingServiceImpl implements RankingService {

    /**
     * 记录 Redis 降级、脏榜单成员等可恢复问题，便于排查榜单数据与资料状态不一致的原因。
     */
    private static final Logger log = LoggerFactory.getLogger(RankingServiceImpl.class);

    /**
     * 接口默认返回数量与 API 文档保持一致，避免调用方未传 limit 时读取过多成员。
     */
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    /**
     * 热门资料默认展示周榜；热门搜索词默认展示日榜，分别符合两个业务场景的时效性。
     */
    private static final RankingPeriod DEFAULT_RESOURCE_PERIOD = RankingPeriod.WEEKLY;
    private static final RankingPeriod DEFAULT_SEARCH_KEYWORD_PERIOD = RankingPeriod.DAILY;

    /**
     * 每次从 ZSet 拉取的候选数量，允许下架或分类不匹配资料被过滤后仍有机会补足榜单。
     */
    private static final int MIN_CANDIDATE_BATCH_SIZE = 20;

    /**
     * 单次查询最多扫描 500 个候选成员，防止大量脏数据导致公开接口无限访问 Redis。
     */
    private static final int MAX_CANDIDATE_SCAN_SIZE = 500;

    /**
     * 热度权重集中维护在排行榜模块，调用方只表达业务事件，避免下载、收藏和审核模块散落魔法分值。
     */
    private static final double DOWNLOAD_HEAT_DELTA = 5D;
    private static final double FAVORITE_HEAT_DELTA = 3D;
    private static final double UNFAVORITE_HEAT_DELTA = -3D;

    /**
     * MySQL 资料数据源：Redis 只负责给出候选 ID 和实时分数，公开资料字段及状态必须以此为准。
     */
    private final ResourceMapper resourceMapper;

    /**
     * Redis 是可降级依赖，测试切片或异常环境未装配时仍可走资料榜 MySQL 兜底。
     */
    private final ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider;

    /**
     * 与总榜重建共用的 Redisson 读写锁客户端。实时写 all 榜时持有读锁，确保重建的 RENAME 不会覆盖并发增量。
     */
    private final RedissonClient redissonClient;

    public RankingServiceImpl(
            ResourceMapper resourceMapper,
            ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider,
            @Lazy RedissonClient redissonClient) {
        this.resourceMapper = resourceMapper;
        this.stringRedisTemplateProvider = stringRedisTemplateProvider;
        this.redissonClient = redissonClient;
    }

    /**
     * 查询热门资料：优先按 Redis 分数倒序读取，再批量补齐审核通过资料；Redis 失败时降级 MySQL 快照。
     */
    @Override
    public List<HotResourceRankingVO> listHotResources(HotResourceRankingQueryDTO query) {
        // 先把默认值、分类和周期归一化，后续 Redis Key 与 Mapper 参数只能使用校验后的值。
        ResolvedResourceRankingQuery resolvedQuery = resolveResourceRankingQuery(query);
        StringRedisTemplate stringRedisTemplate = stringRedisTemplateProvider.getIfAvailable();
        if (stringRedisTemplate == null) {
            // Redis 未装配时不把运营查询变成服务不可用，直接使用 MySQL 热度快照。
            return listHotResourcesFromMysql(resolvedQuery);
        }

        try {
            return listHotResourcesFromRedis(stringRedisTemplate, resolvedQuery);
        } catch (RuntimeException ex) {
            // 实时榜读取失败不能让首页不可用，改用 resource.hot_score 快照提供兜底结果。
            log.warn("查询 Redis 热门资料榜失败，降级 MySQL: period={}, categoryId={}",
                    resolvedQuery.period().getCode(), resolvedQuery.categoryId(), ex);
            return listHotResourcesFromMysql(resolvedQuery);
        }
    }

    /**
     * 查询热门搜索词：搜索词只属于可降级运营数据，Redis 缺失或异常时返回空列表。
     */
    @Override
    public List<HotSearchKeywordRankingVO> listHotSearchKeywords(HotSearchKeywordRankingQueryDTO query) {
        // 搜索词不属于交易数据，参数通过后允许 Redis 故障时降级为空结果。
        ResolvedSearchKeywordRankingQuery resolvedQuery = resolveSearchKeywordRankingQuery(query);
        StringRedisTemplate stringRedisTemplate = stringRedisTemplateProvider.getIfAvailable();
        if (stringRedisTemplate == null) {
            return List.of();
        }

        try {
            // 热词 ZSet 的 member 是归一化关键词，score 是累计搜索次数。
            String key = RedisKeyConstants.searchKeywordRank(resolvedQuery.period().getCode());
            Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet()
                    .reverseRangeWithScores(key, 0, resolvedQuery.limit() - 1L);
            if (tuples == null || tuples.isEmpty()) {
                return List.of();
            }

            List<HotSearchKeywordRankingVO> rankings = new ArrayList<>();
            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                String keyword = tuple.getValue();
                Double score = tuple.getScore();
                if (!StringUtils.hasText(keyword) || score == null) {
                    // 脏 member 不应阻断榜单；名次按实际返回记录连续编号。
                    log.warn("跳过非法热门搜索词榜单成员: keyword={}", keyword);
                    continue;
                }
                rankings.add(new HotSearchKeywordRankingVO(
                        rankings.size() + 1,
                        keyword.trim(),
                        // 当前写入端只执行 +1，因此 score 应为整数；longValue 用于匹配 API 的 searchCount 类型。
                        score.longValue()));
                if (rankings.size() == resolvedQuery.limit()) {
                    break;
                }
            }
            return rankings;
        } catch (RuntimeException ex) {
            log.warn("查询 Redis 热门搜索词榜失败，返回空列表: period={}",
                    resolvedQuery.period().getCode(), ex);
            return List.of();
        }
    }

    /**
     * 有效下载写入四个周期榜；下载去重和增量统计由 DownloadService 先完成，本方法只处理可降级的排行榜副作用。
     */
    @Override
    public void recordResourceDownload(Long resourceId) {
        adjustResourceHeat(resourceId, DOWNLOAD_HEAT_DELTA, "download");
    }

    /**
     * 真实收藏成功后写入四个周期榜，重复收藏不会到达这里，从而保证热度与收藏状态变更一一对应。
     */
    @Override
    public void recordResourceFavorite(Long resourceId) {
        adjustResourceHeat(resourceId, FAVORITE_HEAT_DELTA, "favorite");
    }

    /**
     * 真实取消收藏成功后扣减四个周期榜分数；允许得到负分，后续总榜重建会以持久化统计快照校准。
     */
    @Override
    public void recordResourceUnfavorite(Long resourceId) {
        adjustResourceHeat(resourceId, UNFAVORITE_HEAT_DELTA, "unfavorite");
    }

    /**
     * 审核通过仅补齐缺失成员，不重置已有热度；使用 ZINCRBY 0 可同时覆盖首次创建和幂等重试。
     */
    @Override
    public void initializeApprovedResource(Long resourceId) {
        adjustResourceHeat(resourceId, 0D, "approve");
    }

    /**
     * 下架后的资料必须主动从四个周期榜移除，避免仅依赖查询时过滤造成 Redis 榜单持续堆积脏成员。
     */
    @Override
    public void removeOfflineResource(Long resourceId) {
        if (!isValidResourceId(resourceId)) {
            return;
        }
        StringRedisTemplate stringRedisTemplate = stringRedisTemplateProvider.getIfAvailable();
        if (stringRedisTemplate == null) {
            return;
        }
        try {
            ZSetOperations<String, String> zSetOperations = stringRedisTemplate.opsForZSet();
            String member = String.valueOf(resourceId);
            for (RankingPeriod period : RankingPeriod.values()) {
                if (period == RankingPeriod.ALL) {
                    removeFromAllHotRankingUnderReadLock(zSetOperations, member);
                    continue;
                }
                zSetOperations.remove(RedisKeyConstants.resourceHotRank(period.getCode()), member);
            }
        } catch (RuntimeException ex) {
            // 排行榜属于可重建的派生数据；移除失败不能回滚已提交的下架状态。
            log.warn("下架资料移除 Redis 热门榜失败: resourceId={}", resourceId, ex);
        }
    }

    /**
     * 统一执行四周期 ZSet 分值变更，并为有 TTL 的周期续期；Redis 不可用时只记录日志，主业务保持可用。
     */
    private void adjustResourceHeat(Long resourceId, double delta, String event) {
        if (!isValidResourceId(resourceId)) {
            return;
        }
        StringRedisTemplate stringRedisTemplate = stringRedisTemplateProvider.getIfAvailable();
        if (stringRedisTemplate == null) {
            return;
        }
        try {
            ZSetOperations<String, String> zSetOperations = stringRedisTemplate.opsForZSet();
            String member = String.valueOf(resourceId);
            for (RankingPeriod period : RankingPeriod.values()) {
                String key = RedisKeyConstants.resourceHotRank(period.getCode());
                if (period == RankingPeriod.ALL) {
                    adjustAllHotRankingUnderReadLock(zSetOperations, member, delta);
                    continue;
                }
                zSetOperations.incrementScore(key, member, delta);
                // all 总榜不设置过期时间，其余周期通过 TTL 限制 Redis 历史数据的保留窗口。
                period.getTtl().ifPresent(ttl -> stringRedisTemplate.expire(key, ttl));
            }
        } catch (RuntimeException ex) {
            log.warn("写入 Redis 资料热度失败: event={}, resourceId={}, delta={}", event, resourceId, delta, ex);
        }
    }

    /**
     * 实时热度写入总榜前获取读锁。若手动或定时重建已持有写锁，当前线程会等待其 RENAME 完成，
     * 随后再对新总榜执行 ZINCRBY，避免新增热度被旧榜替换操作丢失。
     */
    private void adjustAllHotRankingUnderReadLock(
            ZSetOperations<String, String> zSetOperations,
            String member,
            double delta) {
        withAllHotRankReadLock(() -> zSetOperations.incrementScore(
                RedisKeyConstants.resourceHotRank(RankingPeriod.ALL.getCode()), member, delta));
    }

    /**
     * 下架移除也必须受同一读锁保护：重建完成后再移除成员，防止下架资料被构建中的临时榜重新带回总榜。
     */
    private void removeFromAllHotRankingUnderReadLock(
            ZSetOperations<String, String> zSetOperations,
            String member) {
        withAllHotRankReadLock(() -> zSetOperations.remove(
                RedisKeyConstants.resourceHotRank(RankingPeriod.ALL.getCode()), member));
    }

    /**
     * 不指定 leaseTime 的阻塞式读锁会启用 Redisson 看门狗。实时写入宁可在重建窗口短暂等待，
     * 也不绕过锁直接写 Redis，否则 RENAME 与 ZINCRBY 并发时仍可能丢失热度。
     */
    private void withAllHotRankReadLock(Runnable operation) {
        RLock readLock = null;
        try {
            RReadWriteLock readWriteLock = redissonClient.getReadWriteLock(
                    RedisKeyConstants.HOT_RANK_MAINTENANCE_LOCK);
            readLock = readWriteLock.readLock();
            readLock.lock();
            operation.run();
        } finally {
            if (readLock != null && readLock.isHeldByCurrentThread()) {
                readLock.unlock();
            }
        }
    }

    private boolean isValidResourceId(Long resourceId) {
        return resourceId != null && resourceId > 0;
    }

    /**
     * 分段读取 Redis 候选，因下架、删除或分类筛选被过滤的成员会继续从后续区间补足。
     */
    private List<HotResourceRankingVO> listHotResourcesFromRedis(
            StringRedisTemplate stringRedisTemplate,
            ResolvedResourceRankingQuery query) {
        // 资料榜的 member 是 resourceId，score 是实时热度分；不同周期通过不同 Key 隔离。
        String key = RedisKeyConstants.resourceHotRank(query.period().getCode());
        // 分类筛选或下架资料会淘汰候选，因此单批读取量要大于最终 limit，才有机会补足榜单。
        int candidateBatchSize = Math.max(MIN_CANDIDATE_BATCH_SIZE, query.limit() * 2);
        // Redis ZSet 的 offset 从 0 开始；每轮推进一个候选批次，避免重复读取同一成员。
        int scanOffset = 0;
        // 只保存最终可公开的记录，rank 由此列表长度生成，保证跳过脏数据后名次仍连续。
        List<HotResourceRankingVO> rankings = new ArrayList<>();

        while (rankings.size() < query.limit() && scanOffset < MAX_CANDIDATE_SCAN_SIZE) {
            // 最后一页不能越过扫描上限，防止异常 Key 中的大量无效成员放大 Redis 与 MySQL 压力。
            //计算当前批次结束下标
            int scanEnd = Math.min(scanOffset + candidateBatchSize - 1, MAX_CANDIDATE_SCAN_SIZE - 1);
            Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet()
                    .reverseRangeWithScores(key, scanOffset, scanEnd);
            if (tuples == null || tuples.isEmpty()) {
                break;
            }

            List<RankCandidate> candidates = extractResourceCandidates(tuples);
            appendVisibleResourcesInRedisOrder(rankings, candidates, query);
            if (tuples.size() < candidateBatchSize) {
                // 本页已到 ZSet 末尾，后续不存在候选，不能再补足时直接结束。
                break;
            }
            scanOffset += candidateBatchSize;
        }
        return rankings;
    }

    /**
     * 将单次 ZSet 结果转换为可查询的候选 ID；非法 ID 或缺失分数的成员只记录日志并跳过。
     */
    private List<RankCandidate> extractResourceCandidates(Set<ZSetOperations.TypedTuple<String>> tuples) {
        List<RankCandidate> candidates = new ArrayList<>();
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            Long resourceId = parsePositiveResourceId(tuple.getValue());
            Double hotScore = tuple.getScore();
            if (resourceId == null || hotScore == null) {
                log.warn("跳过非法热门资料榜单成员: resourceId={}, score={}", tuple.getValue(), hotScore);
                continue;
            }
            candidates.add(new RankCandidate(resourceId, hotScore));
        }
        return candidates;
    }

    /**
     * Mapper 的 IN 查询不保证顺序，因此先按 ID 建索引，再按 Redis 原始候选顺序组装 VO。
     */
    private void appendVisibleResourcesInRedisOrder(
            List<HotResourceRankingVO> rankings,
            List<RankCandidate> candidates,
            ResolvedResourceRankingQuery query) {
        if (candidates.isEmpty() || rankings.size() >= query.limit()) {
            return;
        }

        List<Long> candidateIds = candidates.stream()
                .map(RankCandidate::resourceId)
                .toList();
        // Mapper 固定查询 APPROVED 资料；空集合在进入本方法前已短路，避免生成空 IN SQL。
        List<Resource> resources = resourceMapper.selectApprovedRankingCandidatesByIds(candidateIds, query.categoryId());
        // SQL 的 IN 查询不保证排序，用 Map 保存资料详情后再按 Redis 候选顺序取回。
        Map<Long, Resource> resourcesById = new HashMap<>();
        if (resources != null) {
            for (Resource resource : resources) {
                if (isVisibleForRanking(resource, query.categoryId())) {
                    // 理论上同一资料只会出现一次；putIfAbsent 防御异常 Mapper 返回重复 ID 覆盖首条结果。
                    resourcesById.putIfAbsent(resource.getId(), resource);
                }
            }
        }

        for (RankCandidate candidate : candidates) {
            Resource resource = resourcesById.get(candidate.resourceId());
            if (resource == null) {
                // 资料可能已下架、删除、分类不匹配或 Redis 留有陈旧 member，继续处理下一候选。
                continue;
            }
            rankings.add(toHotResourceRankingVO(
                    rankings.size() + 1,
                    resource,
                    BigDecimal.valueOf(candidate.hotScore())));
            if (rankings.size() == query.limit()) {
                return;
            }
        }
    }

    /**
     * MySQL 兜底只使用已审核通过资料和热度快照，周期榜在降级时会退化为总榜快照语义。
     */
    private List<HotResourceRankingVO> listHotResourcesFromMysql(ResolvedResourceRankingQuery query) {
        // MySQL 仅保存热度快照，所以此路径可用但无法严格还原 daily/weekly/monthly 的实时周期语义。
        List<Resource> resources = resourceMapper.selectHotApprovedResources(query.categoryId(), query.limit());
        if (resources == null || resources.isEmpty()) {
            return List.of();
        }

        List<HotResourceRankingVO> rankings = new ArrayList<>();
        for (Resource resource : resources) {
            if (!isVisibleForRanking(resource, query.categoryId())) {
                continue;
            }
            // 数据库设计中 hot_score 非空；这里仍以 0 兜底，避免历史脏数据让整个公开榜单失败。
            BigDecimal hotScore = resource.getHotScore() == null ? BigDecimal.ZERO : resource.getHotScore();
            rankings.add(toHotResourceRankingVO(rankings.size() + 1, resource, hotScore));
            if (rankings.size() == query.limit()) {
                break;
            }
        }
        return rankings;
    }

    /**
     * Service 层再次校验公开可见性和分类，避免 Mapper 异常结果意外暴露非公开资料。
     */
    private boolean isVisibleForRanking(Resource resource, Long categoryId) {
        return resource != null
                && resource.getId() != null
                && resource.isApproved()
                && (categoryId == null || categoryId.equals(resource.getCategoryId()));
    }

    /**
     * 将已确认可公开的资料转换为榜单 VO，确保不会直接向前端暴露 Resource 的文件、上传者和审核字段。
     */
    private HotResourceRankingVO toHotResourceRankingVO(int rank, Resource resource, BigDecimal hotScore) {
        return new HotResourceRankingVO(
                rank,
                resource.getId(),
                resource.getTitle(),
                resource.getCourseName(),
                resource.getDownloadCount(),
                resource.getFavoriteCount(),
                hotScore);
    }

    /**
     * 排行榜参数在 Service 再次校验，避免绕过 Controller 直接调用时突破 limit、分类和周期边界。
     */
    private ResolvedResourceRankingQuery resolveResourceRankingQuery(HotResourceRankingQueryDTO query) {
        HotResourceRankingQueryDTO safeQuery = query == null ? new HotResourceRankingQueryDTO() : query;
        int limit = resolveLimit(safeQuery.getLimit());
        Long categoryId = resolveCategoryId(safeQuery.getCategoryId());
        RankingPeriod period = resolvePeriod(
                safeQuery.getPeriod(),
                DEFAULT_RESOURCE_PERIOD,
                false,
                "热门资料排行榜周期不合法");
        return new ResolvedResourceRankingQuery(limit, categoryId, period);
    }

    private ResolvedSearchKeywordRankingQuery resolveSearchKeywordRankingQuery(HotSearchKeywordRankingQueryDTO query) {
        // 查询对象可为空，空对象会使用日榜和默认数量，便于 Service 被非 HTTP 调用方安全复用。
        HotSearchKeywordRankingQueryDTO safeQuery = query == null
                ? new HotSearchKeywordRankingQueryDTO()
                : query;
        int limit = resolveLimit(safeQuery.getLimit());
        RankingPeriod period = resolvePeriod(
                safeQuery.getPeriod(),
                DEFAULT_SEARCH_KEYWORD_PERIOD,
                true,
                "热门搜索词排行榜周期不合法");
        return new ResolvedSearchKeywordRankingQuery(limit, period);
    }

    /**
     * 统一处理空 limit 与边界限制，避免 Controller 校验缺失时一次查询过多 Redis 成员或数据库记录。
     */
    private int resolveLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "limit 必须在 1 到 50 之间");
        }
        return limit;
    }

    /**
     * 分类筛选只接受正数；null 表示不限制分类，不能把 0 当作“全部”传给 Mapper。
     */
    private Long resolveCategoryId(Long categoryId) {
        if (categoryId != null && categoryId <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "分类 ID 必须大于 0");
        }
        return categoryId;
    }

    /**
     * 将外部字符串映射为周期白名单；搜索词榜额外排除 all，防止无限累积的总榜被误用于运营热词。
     *接收 rawPeriod
      ↓
rawPeriod 是否为空或只有空格？
      │
      ├── 是 → 返回 defaultPeriod
      │
      └── 否
           ↓
根据 code 查找 RankingPeriod
           │
           ├── 找不到 → 抛参数异常
           │
           └── 找到
                 ↓
当前是否为搜索关键词排行榜？
           │
           ├── 否 → 直接允许该周期
           │
           └── 是
                 ↓
该周期是否支持搜索关键词排行榜？
           │
           ├── 是 → 返回该周期
           └── 否 → 抛参数异常
     */
    private RankingPeriod resolvePeriod(
            String rawPeriod,
            RankingPeriod defaultPeriod,
            boolean searchKeywordRanking,
            String errorMessage) {
        if (!StringUtils.hasText(rawPeriod)) {
            return defaultPeriod;
        }
        return RankingPeriod.fromCode(rawPeriod)
                .filter(period -> !searchKeywordRanking || period.isSearchKeywordRankingSupported())
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_ERROR, errorMessage));
    }

    /**
     * Redis member 来自外部缓存，不能假定格式正确；无法解析或非正数时返回 null 交由调用方跳过。
     */
    private Long parsePositiveResourceId(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            long resourceId = Long.parseLong(value.trim());
            return resourceId > 0 ? resourceId : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * Redis ZSet 的候选资料 ID 与实时热度分，保持 Redis 读取顺序以便后续恢复排名。
     */
    private record RankCandidate(Long resourceId, Double hotScore) {
    }

    private record ResolvedResourceRankingQuery(int limit, Long categoryId, RankingPeriod period) {
    }

    private record ResolvedSearchKeywordRankingQuery(int limit, RankingPeriod period) {
    }
}
