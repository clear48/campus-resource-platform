package com.john.campus.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.john.campus.common.RedisKeyConstants;
import com.john.campus.entity.Resource;
import com.john.campus.service.ResourceDetailCacheService;
import com.john.campus.vo.ResourceDetailVO;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 公开资料详情缓存实现，Redis 仅作为可降级的 MySQL 旁路加速层。
 */
@Service
public class ResourceDetailCacheServiceImpl implements ResourceDetailCacheService {

    private static final Logger log = LoggerFactory.getLogger(ResourceDetailCacheServiceImpl.class);

    /**
     * 固定 30 分钟并叠加 0~5 分钟随机抖动，降低大量详情同时过期造成的回源峰值。
     */
    private static final Duration BASE_TTL = Duration.ofMinutes(30);
    private static final long MAX_TTL_JITTER_SECONDS = Duration.ofMinutes(5).toSeconds();
    /**
     * 读侧只等待有限时间，避免 Redisson 异常时无限占用 HTTP 线程。
     */
    private static final long RESOURCE_LOCK_WAIT_MILLIS = 2_000L;

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final RedissonClient redissonClient;

    @Autowired
    public ResourceDetailCacheServiceImpl(
            StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper,
            @Lazy RedissonClient redissonClient) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.redissonClient = redissonClient;
    }

    /**
     * 保留测试构造入口；未提供 Redisson 时按锁故障降级回源 MySQL。
     */
    public ResourceDetailCacheServiceImpl(
            StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.redissonClient = null;
    }

    /**
     * 坏 JSON、错误资料 ID、非公开状态或用户态字段都会被视为污染值并主动删除，然后由调用方回源 MySQL。
     */
    @Override
    public Optional<ResourceDetailVO> getPublicDetail(long resourceId) {
        String cacheKey = RedisKeyConstants.resourceDetail(resourceId);
        final String cachedJson;
        try {
            cachedJson = stringRedisTemplate.opsForValue().get(cacheKey);
        } catch (RuntimeException ex) {
            log.warn("读取公开资料详情缓存失败，降级查询 MySQL，resourceId={}", resourceId, ex);
            return Optional.empty();
        }
        if (cachedJson == null) {
            return Optional.empty();
        }
        if (!StringUtils.hasText(cachedJson)) {
            log.warn("公开资料详情缓存为空白坏值，删除后回源，resourceId={}", resourceId);
            deleteQuietly(resourceId);
            return Optional.empty();
        }

        try {
            ResourceDetailVO cachedDetail = objectMapper.readValue(cachedJson, ResourceDetailVO.class);
            if (!isValidPublicSnapshot(resourceId, cachedDetail)) {
                log.warn("公开资料详情缓存校验失败，删除坏值并回源，resourceId={}", resourceId);
                deleteQuietly(resourceId);
                return Optional.empty();
            }
            return Optional.of(cachedDetail);
        } catch (JsonProcessingException | RuntimeException ex) {
            log.warn("解析公开资料详情缓存失败，删除坏值并回源，resourceId={}", resourceId, ex);
            deleteQuietly(resourceId);
            return Optional.empty();
        }
    }

    /**
     * 热点 miss 使用每资料互斥锁收口二次检查和回填；竞争、线程中断或 Redisson 异常时只执行一次 loader，
     * 且绝不在锁外回填，从而避免慢请求在审核失效之后重新写入旧快照。
     */
    @Override
    public ResourceDetailVO getOrLoad(long resourceId, Supplier<ResourceDetailVO> loader) {
        Objects.requireNonNull(loader, "公开资料详情 loader 不能为空");
        Optional<ResourceDetailVO> cachedDetail = getPublicDetail(resourceId);
        if (cachedDetail.isPresent()) {
            return cachedDetail.get();
        }
        if (redissonClient == null) {
            return loader.get();
        }

        RLock resourceLock;
        boolean locked;
        try {
            resourceLock = redissonClient.getLock(RedisKeyConstants.resourceDetailLock(resourceId));
            // 不指定 leaseTime，持锁期间由现有 Redisson 看门狗续期。
            locked = resourceLock.tryLock(RESOURCE_LOCK_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("等待公开资料详情缓存锁被中断，直接回源且不回填，resourceId={}", resourceId, ex);
            return loader.get();
        } catch (RuntimeException ex) {
            log.warn("获取公开资料详情缓存锁失败，直接回源且不回填，resourceId={}", resourceId, ex);
            return loader.get();
        }

        if (!locked) {
            log.debug("公开资料详情缓存锁竞争超时，直接回源且不回填，resourceId={}", resourceId);
            return loader.get();
        }

        try {
            Optional<ResourceDetailVO> doubleCheckedDetail = getPublicDetail(resourceId);
            if (doubleCheckedDetail.isPresent()) {
                return doubleCheckedDetail.get();
            }
            // loader 可能抛出 BusinessException；此处不捕获、不重试，确保原错误语义和单次数据库执行。
            ResourceDetailVO loadedDetail = loader.get();
            cachePublicDetail(loadedDetail);
            return loadedDetail;
        } finally {
            releaseResourceLock(resourceLock, resourceId, "公开详情回源");
        }
    }

    /**
     * 写入前重新构造公共快照，强制把 favorited 置空，防止任何调用方误把当前用户状态放入共享缓存。
     */
    @Override
    public void cachePublicDetail(ResourceDetailVO resourceDetail) {
        if (resourceDetail == null
                || resourceDetail.resourceId() == null
                || resourceDetail.resourceId() <= 0
                || !Integer.valueOf(Resource.STATUS_APPROVED).equals(resourceDetail.status())) {
            log.warn("跳过不符合公开语义的资料详情缓存写入");
            return;
        }

        ResourceDetailVO publicSnapshot = toPublicSnapshot(resourceDetail);
        try {
            String cachedJson = objectMapper.writeValueAsString(publicSnapshot);
            stringRedisTemplate.opsForValue().set(
                    RedisKeyConstants.resourceDetail(publicSnapshot.resourceId()),
                    cachedJson,
                    resolveTtl());
        } catch (JsonProcessingException | RuntimeException ex) {
            log.warn("写入公开资料详情缓存失败，保留 MySQL 查询结果，resourceId={}", resourceDetail.resourceId(), ex);
        }
    }

    /**
     * 审核事务提交后删除 Redis 缓存。与 getOrLoad() 使用同一把 Redisson 锁保证有序：
     * DELETE 要么在回填之前完成，要么等回填结束后再删——不会出现在回填过程中交错执行。
     * 锁超时或 Redisson 不可用时降级为直接 DELETE，接受极低概率的脏缓存窗口（TTL 兜底）。
     */
    @Override
    public void invalidate(long resourceId) {
        if (resourceId <= 0) {
            log.warn("跳过非法资料 ID 的详情缓存失效，resourceId={}", resourceId);
            return;
        }
        if (redissonClient == null) {
            deleteQuietly(resourceId);
            return;
        }

        RLock resourceLock = null;
        try {
            resourceLock = redissonClient.getLock(RedisKeyConstants.resourceDetailLock(resourceId));
            if (!resourceLock.tryLock(RESOURCE_LOCK_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                log.warn("缓存失效等待资源锁超时，降级直接删除，resourceId={}", resourceId);
                deleteQuietly(resourceId);
                return;
            }
            deleteQuietly(resourceId);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("缓存失效等待锁被中断，降级直接删除，resourceId={}", resourceId);
            deleteQuietly(resourceId);
        } catch (RuntimeException ex) {
            log.warn("缓存失效获取锁失败，降级直接删除，resourceId={}", resourceId, ex);
            deleteQuietly(resourceId);
        } finally {
            releaseResourceLock(resourceLock, resourceId, "失效");
        }
    }

    private void deleteQuietly(long resourceId) {
        try {
            stringRedisTemplate.delete(RedisKeyConstants.resourceDetail(resourceId));
        } catch (RuntimeException ex) {
            log.warn("删除资料详情缓存失败，resourceId={}", resourceId, ex);
        }
    }

    private boolean isValidPublicSnapshot(long expectedResourceId, ResourceDetailVO cachedDetail) {
        return cachedDetail != null
                && Long.valueOf(expectedResourceId).equals(cachedDetail.resourceId())
                && Integer.valueOf(Resource.STATUS_APPROVED).equals(cachedDetail.status())
                && cachedDetail.favorited() == null;
    }

    private ResourceDetailVO toPublicSnapshot(ResourceDetailVO detail) {
        return new ResourceDetailVO(
                detail.resourceId(),
                detail.title(),
                detail.description(),
                detail.categoryId(),
                detail.categoryName(),
                detail.courseName(),
                detail.resourceType(),
                detail.tags(),
                detail.status(),
                detail.downloadCount(),
                detail.favoriteCount(),
                detail.hotScore(),
                detail.createdAt(),
                null);
    }

    private Duration resolveTtl() {
        long jitterSeconds = ThreadLocalRandom.current().nextLong(MAX_TTL_JITTER_SECONDS + 1);
        return BASE_TTL.plusSeconds(jitterSeconds);
    }

    /**
     * Redisson 只允许持锁线程释放；归属检查和解锁异常仅告警，由看门狗超时兜底。
     */
    private void releaseResourceLock(RLock resourceLock, long resourceId, String phase) {
        if (resourceLock == null) {
            return;
        }
        try {
            if (resourceLock.isHeldByCurrentThread()) {
                resourceLock.unlock();
            }
        } catch (RuntimeException ex) {
            log.warn("公开资料详情缓存{}后释放锁失败，resourceId={}", phase, resourceId, ex);
        }
    }
}
