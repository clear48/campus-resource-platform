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

    /**
     * Redis 缓存属于可降级依赖，发生异常时只记录日志，不能影响收藏主流程。
     */
    private static final Logger log = LoggerFactory.getLogger(FavoriteServiceImpl.class);
    /**
     * 用户收藏 Set 的缓存时长。到期后通过 MySQL 有效收藏记录重建，避免长期缓存与数据库状态偏离。
     */
    private static final Duration FAVORITE_CACHE_TTL = Duration.ofMinutes(30);
    /**
     * 我的收藏列表的默认分页参数，与 PageQuery 的默认值保持一致。
     */
    private static final int DEFAULT_PAGE_NO = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    /**
     * 单页上限，避免用户收藏量较大时一次查询和响应体过大。
     */
    private static final int MAX_PAGE_SIZE = 100;
    /**
     * 资料收藏数的增减值必须集中定义，调用 Mapper 时只传递 +1 或 -1，避免散落魔法值。
     */
    private static final int FAVORITE_COUNT_INCREMENT = 1;
    private static final int FAVORITE_COUNT_DECREMENT = -1;
    /**
     * 热度 ZSet 联动尚未进入收藏模块首版，因此接口仍保留字段但固定返回 0。
     */
    private static final int HOT_SCORE_DELTA_NOT_IMPLEMENTED = 0;

    /**
     * 收藏关系的最终数据源。唯一索引和状态字段由该 Mapper 提供，负责幂等与软状态复用的数据库基础。
     */
    private final FavoriteMapper favoriteMapper;
    /**
     * 资料状态、资料展示信息和收藏数快照均来自 resource 表。
     */
    private final ResourceMapper resourceMapper;
    /**
     * 可选 Redis 依赖。测试切片或 Redis 未装配时可返回 null，使收藏主流程仍能以 MySQL 正常工作。
     */
    private final ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider;
    /**
     * 显式控制 MySQL 事务边界：只包裹 favorite 和 resource 的写操作，提交后才执行 Redis 同步。
     */
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
        // 用户身份只能从 JWT 上下文读取，避免客户端伪造 userId 收藏他人资料。
        Long userId = UserContextHolder.getRequiredUserId();

        FavoriteResultVO result;
        try {
            // 事务内同时变更收藏关系和资料收藏数，任一数据库写入失败都会整体回滚。
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
        // 条件状态更新和收藏数递减必须在同一事务内，防止并发取消导致关系与计数不一致。
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
                    // Key 存在说明该用户的有效收藏集合已经加载完成，SISMEMBER 可直接给出 O(1) 判断结果。
                    boolean favorited = Boolean.TRUE.equals(
                            stringRedisTemplate.opsForSet().isMember(cacheKey, String.valueOf(resourceId)));
                    return new FavoriteStatusVO(resourceId, favorited);
                }
            } catch (RuntimeException ex) {
                // Redis 只是缓存加速层，访问失败时降级查询 MySQL。
                log.warn("查询收藏缓存失败，降级 MySQL: userId={}, resourceId={}", userId, resourceId, ex);
            }
        }

        // 缓存 Key 不存在时以 MySQL 为准；随后批量重建整组收藏，避免下次逐条查询数据库。
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
        // Mapper 使用 MySQL LIMIT 偏移量，分页计算集中在 Service，Controller 不参与数据库细节。
        int offset = (pageNo - 1) * pageSize;

        List<Favorite> favorites = favoriteMapper.selectByUser(userId, offset, pageSize);
        long total = favoriteMapper.countByUser(userId);
        // 先批量读取资料，避免对列表中的每条收藏分别查询 resource 表形成 N+1 问题。
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
            // 用户已收藏时不再变更计数，直接返回幂等成功。
            return toFavoriteResult(resource, true, true);
        }

        if (existing == null) {
            // 首次收藏必须写入 userId + resourceId；数据库唯一索引会兜底并发插入。
            Favorite favorite = new Favorite();
            favorite.setUserId(userId);
            favorite.setResourceId(resourceId);
            favorite.setStatus(Favorite.STATUS_FAVORITED);
            favoriteMapper.insert(favorite);
        } else {
            // 已取消的历史记录不物理删除，使用 0 -> 1 条件更新恢复收藏关系。
            int changed = favoriteMapper.updateStatus(
                    existing.getId(), Favorite.STATUS_CANCELED, Favorite.STATUS_FAVORITED);
            if (changed == 0) {
                // 并发请求可能已先恢复收藏；重新读取后若已生效，仍按幂等成功返回。
                Favorite latestFavorite = favoriteMapper.selectByUserAndResource(userId, resourceId);
                if (isFavorited(latestFavorite)) {
                    return toFavoriteResult(loadResource(resourceId), true, true);
                }
                throw new BusinessException(ErrorCode.RESOURCE_STATUS_INVALID, "收藏状态已变化，请重试");
            }
        }

        // 只有新增或成功恢复收藏才增加计数，SQL 内部原子完成，避免读改写竞争。
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
            // 取消不存在或已取消的记录没有可递减的计数，统一视为资源不存在。
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "收藏记录不存在或已取消");
        }

        // 条件更新要求旧状态仍为已收藏，保证两个并发取消请求至多一个进入计数递减分支。
        int changed = favoriteMapper.updateStatus(
                existing.getId(), Favorite.STATUS_FAVORITED, Favorite.STATUS_CANCELED);
        if (changed == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "收藏记录不存在或已取消");
        }
        // 负增量 SQL 带 favorite_count > 0 条件，数据库层再防一次收藏数出现负值。
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
            // 另一个并发请求已完成收藏，当前请求无需再执行任何写操作。
            return toFavoriteResult(loadResource(resourceId), true, true);
        }
        if (existing == null) {
            // 唯一索引异常后仍查不到记录，说明写入结果不可确认，不能误报成功。
            throw new BusinessException(ErrorCode.FAVORITE_DUPLICATE, "收藏记录创建失败，请重试");
        }
        // 记录存在但已取消时，在新的事务中尝试恢复状态，避免复用已结束的失败事务。
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
            // 空页直接返回不可变空 Map，避免 Mapper 生成空 IN 条件。
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
            // Redis 未启用时 MySQL 结果仍已提交，缓存只是少一次加速机会。
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
        // 即使 Controller 未经过 Bean Validation 直接调用 Service，也保持分页参数的业务兜底。
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
        // 与 PageQuery 的注解约束保持一致，防止其他调用方绕过 Controller 校验。
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
