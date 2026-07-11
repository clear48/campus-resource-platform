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

    private final ResourceMapper resourceMapper;

    /**
     * Redis 是可降级依赖，测试切片或异常环境未装配时仍可走资料榜 MySQL 兜底。
     */
    private final ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider;

    public RankingServiceImpl(
            ResourceMapper resourceMapper,
            ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider) {
        this.resourceMapper = resourceMapper;
        this.stringRedisTemplateProvider = stringRedisTemplateProvider;
    }

    /**
     * 查询热门资料：优先按 Redis 分数倒序读取，再批量补齐审核通过资料；Redis 失败时降级 MySQL 快照。
     */
    @Override
    public List<HotResourceRankingVO> listHotResources(HotResourceRankingQueryDTO query) {
        ResolvedResourceRankingQuery resolvedQuery = resolveResourceRankingQuery(query);
        StringRedisTemplate stringRedisTemplate = stringRedisTemplateProvider.getIfAvailable();
        if (stringRedisTemplate == null) {
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
        ResolvedSearchKeywordRankingQuery resolvedQuery = resolveSearchKeywordRankingQuery(query);
        StringRedisTemplate stringRedisTemplate = stringRedisTemplateProvider.getIfAvailable();
        if (stringRedisTemplate == null) {
            return List.of();
        }

        try {
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
     * 分段读取 Redis 候选，因下架、删除或分类筛选被过滤的成员会继续从后续区间补足。
     */
    private List<HotResourceRankingVO> listHotResourcesFromRedis(
            StringRedisTemplate stringRedisTemplate,
            ResolvedResourceRankingQuery query) {
        String key = RedisKeyConstants.resourceHotRank(query.period().getCode());
        int candidateBatchSize = Math.max(MIN_CANDIDATE_BATCH_SIZE, query.limit() * 2);
        int scanOffset = 0;
        List<HotResourceRankingVO> rankings = new ArrayList<>();

        while (rankings.size() < query.limit() && scanOffset < MAX_CANDIDATE_SCAN_SIZE) {
            int scanEnd = Math.min(scanOffset + candidateBatchSize - 1, MAX_CANDIDATE_SCAN_SIZE - 1);
            Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet()
                    .reverseRangeWithScores(key, scanOffset, scanEnd);
            if (tuples == null || tuples.isEmpty()) {
                break;
            }

            List<RankCandidate> candidates = extractResourceCandidates(tuples);
            appendVisibleResourcesInRedisOrder(rankings, candidates, query);
            if (tuples.size() < candidateBatchSize) {
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
        List<Resource> resources = resourceMapper.selectApprovedRankingCandidatesByIds(candidateIds, query.categoryId());
        Map<Long, Resource> resourcesById = new HashMap<>();
        if (resources != null) {
            for (Resource resource : resources) {
                if (isVisibleForRanking(resource, query.categoryId())) {
                    resourcesById.putIfAbsent(resource.getId(), resource);
                }
            }
        }

        for (RankCandidate candidate : candidates) {
            Resource resource = resourcesById.get(candidate.resourceId());
            if (resource == null) {
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
        List<Resource> resources = resourceMapper.selectHotApprovedResources(query.categoryId(), query.limit());
        if (resources == null || resources.isEmpty()) {
            return List.of();
        }

        List<HotResourceRankingVO> rankings = new ArrayList<>();
        for (Resource resource : resources) {
            if (!isVisibleForRanking(resource, query.categoryId())) {
                continue;
            }
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

    private int resolveLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "limit 必须在 1 到 50 之间");
        }
        return limit;
    }

    private Long resolveCategoryId(Long categoryId) {
        if (categoryId != null && categoryId <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "分类 ID 必须大于 0");
        }
        return categoryId;
    }

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
