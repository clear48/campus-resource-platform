package com.john.campus.service.impl;

import com.john.campus.common.ErrorCode;
import com.john.campus.common.PageResult;
import com.john.campus.common.RedisKeyConstants;
import com.john.campus.common.UserContextHolder;
import com.john.campus.dto.PageQuery;
import com.john.campus.entity.Favorite;
import com.john.campus.entity.Resource;
import com.john.campus.exception.BusinessException;
import com.john.campus.mapper.FavoriteMapper;
import com.john.campus.mapper.ResourceMapper;
import com.john.campus.service.FavoriteService;
import com.john.campus.vo.FavoriteResultVO;
import com.john.campus.vo.FavoriteStatusVO;
import com.john.campus.vo.MyFavoriteVO;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 收藏业务实现：MySQL 保存最终状态，Redis Set 仅用于加速收藏状态判断。
 * 数据库写操作由 TransactionTemplate 控制，确保 Redis 同步仅发生在提交成功后。
 */
@Service
public class FavoriteServiceImpl implements FavoriteService {

    private static final Logger log = LoggerFactory.getLogger(FavoriteServiceImpl.class);
    private static final Duration FAVORITE_CACHE_TTL = Duration.ofMinutes(30);
    private static final int DEFAULT_PAGE_NO = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int FAVORITE_COUNT_INCREMENT = 1;
    private static final int FAVORITE_COUNT_DECREMENT = -1;
    private static final int HOT_SCORE_DELTA_NOT_IMPLEMENTED = 0;

    private final FavoriteMapper favoriteMapper;
    private final ResourceMapper resourceMapper;
    private final ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider;
    private final TransactionTemplate transactionTemplate;

    public FavoriteServiceImpl(
            FavoriteMapper favoriteMapper,
            ResourceMapper resourceMapper,
            ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider,
            TransactionTemplate transactionTemplate) {
        this.favoriteMapper = favoriteMapper;
        this.resourceMapper = resourceMapper;
        this.stringRedisTemplateProvider = stringRedisTemplateProvider;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 收藏主流程：在独立 MySQL 事务中完成状态变更和计数更新，提交后再写 Redis Set。
     * 并发插入触发唯一索引时，异常会在事务结束后转换为幂等成功，避免事务被标记为 rollback-only。
     */
    @Override
    public FavoriteResultVO favorite(Long resourceId) {
        validateResourceId(resourceId);
        Long userId = UserContextHolder.getRequiredUserId();

        FavoriteResultVO result;
        try {
            result = requireTransactionResult(transactionTemplate.execute(
                    transactionStatus -> activateFavoriteInTransaction(userId, resourceId)));
        } catch (DuplicateKeyException ex) {
            // 唯一索引是并发重复收藏的最终兜底；事务回滚后再读取赢家记录并按幂等成功返回。
            result = resolveDuplicateFavorite(userId, resourceId);
        }

        // TransactionTemplate.execute 返回时 MySQL 已提交，Redis 失败仅影响缓存命中率。
        addFavoriteToCache(userId, resourceId);
        return result;
    }

    /**
     * 取消收藏：状态切换和收藏数递减在同一 MySQL 事务中，成功提交后移除 Redis 成员。
     */
    @Override
    public FavoriteResultVO unfavorite(Long resourceId) {
        validateResourceId(resourceId);
        Long userId = UserContextHolder.getRequiredUserId();
        FavoriteResultVO result = requireTransactionResult(transactionTemplate.execute(
                transactionStatus -> cancelFavoriteInTransaction(userId, resourceId)));

        removeFavoriteFromCache(userId, resourceId);
        return result;
    }

    /**
     * 查询收藏状态：缓存 Key 存在时直接使用 SISMEMBER；缓存不存在或 Redis 故障时以 MySQL 为准。
     */
    @Override
    public FavoriteStatusVO getFavoriteStatus(Long resourceId) {
        validateResourceId(resourceId);
        Long userId = UserContextHolder.getRequiredUserId();
        StringRedisTemplate stringRedisTemplate = stringRedisTemplateProvider.getIfAvailable();
        String cacheKey = RedisKeyConstants.userFavorites(userId);

        if (stringRedisTemplate != null) {
            try {
                if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(cacheKey))) {
                    boolean favorited = Boolean.TRUE.equals(
                            stringRedisTemplate.opsForSet().isMember(cacheKey, String.valueOf(resourceId)));
                    return new FavoriteStatusVO(resourceId, favorited);
                }
            } catch (RuntimeException ex) {
                // Redis 只是缓存加速层，访问失败时降级查询 MySQL。
                log.warn("查询收藏缓存失败，降级 MySQL: userId={}, resourceId={}", userId, resourceId, ex);
            }
        }

        Favorite favorite = favoriteMapper.selectByUserAndResource(userId, resourceId);
        boolean favorited = isFavorited(favorite);
        rebuildFavoriteCache(userId, stringRedisTemplate);
        return new FavoriteStatusVO(resourceId, favorited);
    }

    /**
     * 我的收藏只按当前用户查询，批量加载资料信息后保持收藏时间倒序。
     */
    @Override
    public PageResult<MyFavoriteVO> listMyFavorites(PageQuery pageQuery) {
        Long userId = UserContextHolder.getRequiredUserId();
        int pageNo = resolvePageNo(pageQuery);
        int pageSize = resolvePageSize(pageQuery);
        int offset = (pageNo - 1) * pageSize;

        List<Favorite> favorites = favoriteMapper.selectByUser(userId, offset, pageSize);
        long total = favoriteMapper.countByUser(userId);
        Map<Long, Resource> resourceMap = loadResources(favorites);
        List<MyFavoriteVO> records = favorites.stream()
                .map(favorite -> toMyFavoriteVO(favorite, resourceMap.get(favorite.getResourceId())))
                .toList();
        return PageResult.of(records, pageNo, pageSize, total);
    }

    /**
     * 事务内激活收藏关系：先二次校验资料状态，再新增或恢复记录，最后原子增加收藏数。
     */
    private FavoriteResultVO activateFavoriteInTransaction(Long userId, Long resourceId) {
        Resource resource = requireApprovedResource(resourceId);
        Favorite existing = favoriteMapper.selectByUserAndResource(userId, resourceId);
        if (isFavorited(existing)) {
            return toFavoriteResult(resource, true, true);
        }

        if (existing == null) {
            Favorite favorite = new Favorite();
            favorite.setUserId(userId);
            favorite.setResourceId(resourceId);
            favorite.setStatus(Favorite.STATUS_FAVORITED);
            favoriteMapper.insert(favorite);
        } else {
            int changed = favoriteMapper.updateStatus(
                    existing.getId(), Favorite.STATUS_CANCELED, Favorite.STATUS_FAVORITED);
            if (changed == 0) {
                Favorite latestFavorite = favoriteMapper.selectByUserAndResource(userId, resourceId);
                if (isFavorited(latestFavorite)) {
                    return toFavoriteResult(loadResource(resourceId), true, true);
                }
                throw new BusinessException(ErrorCode.RESOURCE_STATUS_INVALID, "收藏状态已变化，请重试");
            }
        }

        if (resourceMapper.updateFavoriteCount(resourceId, FAVORITE_COUNT_INCREMENT) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "资料不存在");
        }
        return toFavoriteResult(loadResource(resourceId), true, false);
    }

    /**
     * 事务内取消收藏：状态条件更新成功后才递减收藏数，避免并发重复取消导致多次扣减。
     */
    private FavoriteResultVO cancelFavoriteInTransaction(Long userId, Long resourceId) {
        Favorite existing = favoriteMapper.selectByUserAndResource(userId, resourceId);
        if (!isFavorited(existing)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "收藏记录不存在或已取消");
        }

        int changed = favoriteMapper.updateStatus(
                existing.getId(), Favorite.STATUS_FAVORITED, Favorite.STATUS_CANCELED);
        if (changed == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "收藏记录不存在或已取消");
        }
        if (resourceMapper.updateFavoriteCount(resourceId, FAVORITE_COUNT_DECREMENT) != 1) {
            throw new BusinessException(ErrorCode.RESOURCE_STATUS_INVALID, "资料收藏数异常");
        }
        return toFavoriteResult(loadResource(resourceId), false, false);
    }

    /**
     * 处理并发新增导致的唯一索引异常；当前已收藏则返回幂等成功，取消状态则在新事务中恢复收藏。
     */
    private FavoriteResultVO resolveDuplicateFavorite(Long userId, Long resourceId) {
        Favorite existing = favoriteMapper.selectByUserAndResource(userId, resourceId);
        if (isFavorited(existing)) {
            return toFavoriteResult(loadResource(resourceId), true, true);
        }
        if (existing == null) {
            throw new BusinessException(ErrorCode.FAVORITE_DUPLICATE, "收藏记录创建失败，请重试");
        }
        return requireTransactionResult(transactionTemplate.execute(
                transactionStatus -> activateFavoriteInTransaction(userId, resourceId)));
    }

    /**
     * 校验资料存在且审核通过，待审核、拒绝、下架和删除资料均不能新增收藏。
     */
    private Resource requireApprovedResource(Long resourceId) {
        Resource resource = loadResource(resourceId);
        if (!resource.isApproved()) {
            throw new BusinessException(ErrorCode.RESOURCE_STATUS_INVALID, "资料未审核通过或已下架，不能收藏");
        }
        return resource;
    }

    /**
     * 按 ID 查询资料并统一处理不存在场景。
     */
    private Resource loadResource(Long resourceId) {
        Resource resource = resourceMapper.selectById(resourceId);
        if (resource == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "资料不存在");
        }
        return resource;
    }

    /**
     * 批量查询列表涉及的资料，保持 FavoriteMapper 的收藏时间排序不变。
     */
    private Map<Long, Resource> loadResources(List<Favorite> favorites) {
        List<Long> resourceIds = favorites.stream()
                .map(Favorite::getResourceId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (resourceIds.isEmpty()) {
            return Map.of();
        }
        return resourceMapper.selectByIds(resourceIds).stream()
                .collect(Collectors.toMap(Resource::getId, resource -> resource, (existing, replacement) -> existing));
    }

    /**
     * MySQL 提交后写入 Redis Set，并续期缓存；失败时仅记录日志。
     */
    private void addFavoriteToCache(Long userId, Long resourceId) {
        StringRedisTemplate stringRedisTemplate = stringRedisTemplateProvider.getIfAvailable();
        if (stringRedisTemplate == null) {
            return;
        }
        try {
            String cacheKey = RedisKeyConstants.userFavorites(userId);
            stringRedisTemplate.opsForSet().add(cacheKey, String.valueOf(resourceId));
            stringRedisTemplate.expire(cacheKey, FAVORITE_CACHE_TTL);
        } catch (RuntimeException ex) {
            log.warn("写入收藏缓存失败: userId={}, resourceId={}", userId, resourceId, ex);
        }
    }

    /**
     * MySQL 提交后移除 Redis Set 成员；失败不影响已提交的取消收藏结果。
     */
    private void removeFavoriteFromCache(Long userId, Long resourceId) {
        StringRedisTemplate stringRedisTemplate = stringRedisTemplateProvider.getIfAvailable();
        if (stringRedisTemplate == null) {
            return;
        }
        try {
            stringRedisTemplate.opsForSet()
                    .remove(RedisKeyConstants.userFavorites(userId), String.valueOf(resourceId));
        } catch (RuntimeException ex) {
            log.warn("移除收藏缓存失败: userId={}, resourceId={}", userId, resourceId, ex);
        }
    }

    /**
     * 缓存缺失时从 MySQL 批量重建有效收藏集合；空集合不创建 Key，后续查询仍会以 MySQL 为准。
     */
    private void rebuildFavoriteCache(Long userId, StringRedisTemplate stringRedisTemplate) {
        if (stringRedisTemplate == null) {
            return;
        }
        try {
            List<String> resourceIds = favoriteMapper.selectActiveResourceIdsByUser(userId).stream()
                    .map(String::valueOf)
                    .toList();
            if (resourceIds.isEmpty()) {
                return;
            }
            String cacheKey = RedisKeyConstants.userFavorites(userId);
            stringRedisTemplate.opsForSet().add(cacheKey, resourceIds.toArray(String[]::new));
            stringRedisTemplate.expire(cacheKey, FAVORITE_CACHE_TTL);
        } catch (RuntimeException ex) {
            log.warn("重建收藏缓存失败: userId={}", userId, ex);
        }
    }

    /**
     * 判断收藏实体是否处于有效收藏状态，空记录视为未收藏。
     */
    private boolean isFavorited(Favorite favorite) {
        return favorite != null && Integer.valueOf(Favorite.STATUS_FAVORITED).equals(favorite.getStatus());
    }

    /**
     * 收藏接口统一返回热度变化预留值，排行榜联动尚未纳入首版实现。
     */
    private FavoriteResultVO toFavoriteResult(Resource resource, boolean favorited, boolean duplicateIgnored) {
        return new FavoriteResultVO(
                resource.getId(),
                favorited,
                duplicateIgnored,
                resource.getFavoriteCount(),
                HOT_SCORE_DELTA_NOT_IMPLEMENTED);
    }

    /**
     * 我的收藏列表只展示资料公开字段，资料已删除时保留收藏记录但以空展示字段降级。
     */
    private MyFavoriteVO toMyFavoriteVO(Favorite favorite, Resource resource) {
        return new MyFavoriteVO(
                favorite.getResourceId(),
                resource != null ? resource.getTitle() : null,
                resource != null ? resource.getCourseName() : null,
                resource != null ? resource.getDownloadCount() : null,
                resource != null ? resource.getFavoriteCount() : null,
                resource != null ? resource.getCreatedAt() : null,
                favorite.getCreatedAt());
    }

    /**
     * 路径资料 ID 必须为正数，避免无意义查询进入事务。
     */
    private void validateResourceId(Long resourceId) {
        if (resourceId == null || resourceId <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "资料 ID 不合法");
        }
    }

    private int resolvePageNo(PageQuery pageQuery) {
        Integer pageNo = pageQuery == null ? DEFAULT_PAGE_NO : pageQuery.getPageNo();
        if (pageNo == null) {
            return DEFAULT_PAGE_NO;
        }
        if (pageNo < DEFAULT_PAGE_NO) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "pageNo 必须大于等于 1");
        }
        return pageNo;
    }

    private int resolvePageSize(PageQuery pageQuery) {
        Integer pageSize = pageQuery == null ? DEFAULT_PAGE_SIZE : pageQuery.getPageSize();
        if (pageSize == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "pageSize 必须在 1 到 100 之间");
        }
        return pageSize;
    }

    /**
     * TransactionTemplate 只有在正常提交时返回结果，空结果统一转换为服务端异常。
     */
    private FavoriteResultVO requireTransactionResult(FavoriteResultVO result) {
        if (result == null) {
            throw new BusinessException(ErrorCode.SERVER_ERROR, "收藏事务未返回结果");
        }
        return result;
    }
}
