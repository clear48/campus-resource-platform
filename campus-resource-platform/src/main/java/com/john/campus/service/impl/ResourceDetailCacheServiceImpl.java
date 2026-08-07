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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.TaskScheduler;
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
    private static final Duration MAX_CACHE_TTL = Duration.ofMinutes(35);
    private static final Duration[] INVALIDATION_RETRY_DELAYS = {
            Duration.ofMillis(500), Duration.ofSeconds(2), Duration.ofSeconds(5)
    };
    /**
     * 读侧和失效侧都只等待有限时间，避免 Redis/Redisson 异常时无限占用 HTTP 或调度线程。
     */
    private static final long RESOURCE_LOCK_WAIT_MILLIS = 2_000L;

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final TaskScheduler taskScheduler;
    private final RedissonClient redissonClient;
    /**
     * 当前实例的短期缓存绕过表。失效无法证明已排在旧 reader 之后完成时，最长绕过到缓存 TTL 上限。
     */
    private final ConcurrentHashMap<Long, Instant> bypassUntilByResource = new ConcurrentHashMap<>();

    @Autowired
    public ResourceDetailCacheServiceImpl(
            StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper,
            @Qualifier("resourceDetailCacheTaskScheduler") TaskScheduler taskScheduler,
            @Lazy RedissonClient redissonClient) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.taskScheduler = taskScheduler;
        this.redissonClient = redissonClient;
    }

    /**
     * 保留现有缓存单元测试的构造入口；未提供 Redisson 时按锁故障降级，不改变原缓存读写测试语义。
     */
    public ResourceDetailCacheServiceImpl(
            StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper,
            TaskScheduler taskScheduler) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.taskScheduler = taskScheduler;
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
            deleteQuietly(resourceId, "空白坏值清理");
            return Optional.empty();
        }

        try {
            ResourceDetailVO cachedDetail = objectMapper.readValue(cachedJson, ResourceDetailVO.class);
            if (!isValidPublicSnapshot(resourceId, cachedDetail)) {
                log.warn("公开资料详情缓存校验失败，删除坏值并回源，resourceId={}", resourceId);
                deleteQuietly(resourceId, "坏值清理");
                return Optional.empty();
            }
            return Optional.of(cachedDetail);
        } catch (JsonProcessingException | RuntimeException ex) {
            log.warn("解析公开资料详情缓存失败，删除坏值并回源，resourceId={}", resourceId, ex);
            deleteQuietly(resourceId, "坏 JSON 清理");
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
        if (isCacheBypassed(resourceId)) {
            // 绕过期内既不读也不写 Redis，loader 只执行一次并保留原业务异常。
            return loader.get();
        }
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
     * 失效开始即建立当前实例绕过；同步立即尝试后，再安排 500ms、2s、5s 三次有限重试。
     * 只有某次在同一资源锁内成功执行 Redis 删除命令，才清除本轮绕过标记。
     */
    @Override
    public void invalidateWithDelay(long resourceId) {
        if (resourceId <= 0) {
            log.warn("跳过非法资料 ID 的详情缓存失效，resourceId={}", resourceId);
            return;
        }

        Instant candidateBypassUntil = Instant.now().plus(MAX_CACHE_TTL);
        Instant bypassUntil = bypassUntilByResource.compute(
                resourceId,
                (ignored, existing) -> existing != null && existing.isAfter(candidateBypassUntil)
                        ? existing
                        : candidateBypassUntil);

        boolean immediateDeleteConfirmed = deleteWithResourceLock(resourceId, "立即删除");
        AtomicBoolean retryPlanComplete = new AtomicBoolean(true);
        for (Duration retryDelay : INVALIDATION_RETRY_DELAYS) {
            scheduleInvalidationRetry(resourceId, bypassUntil, retryDelay, retryPlanComplete);
        }
        if (immediateDeleteConfirmed && retryPlanComplete.get()) {
            clearBypass(resourceId, bypassUntil);
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

    private boolean deleteQuietly(long resourceId, String phase) {
        try {
            Boolean deleted = stringRedisTemplate.delete(RedisKeyConstants.resourceDetail(resourceId));
            if (deleted == null) {
                log.warn("公开资料详情缓存{}未返回删除结果，resourceId={}", phase, resourceId);
                return false;
            }
            // false 表示 Key 本就不存在，Redis 命令仍成功完成且已经达到失效目标。
            return true;
        } catch (RuntimeException ex) {
            log.warn("公开资料详情缓存{}失败，resourceId={}", phase, resourceId, ex);
            return false;
        }
    }

    /**
     * 失效侧有限等待同一资料的持锁回填；超时或锁服务异常时仍直接尝试删除，但不能据此清除实例绕过。
     */
    private boolean deleteWithResourceLock(long resourceId, String phase) {
        if (redissonClient == null) {
            deleteQuietly(resourceId, phase);
            return false;
        }

        RLock resourceLock = null;
        try {
            resourceLock = redissonClient.getLock(RedisKeyConstants.resourceDetailLock(resourceId));
            if (!resourceLock.tryLock(RESOURCE_LOCK_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                log.warn("公开资料详情缓存{}等待资源锁超时，降级直接删除，resourceId={}", phase, resourceId);
                deleteQuietly(resourceId, phase + "降级");
                return false;
            }
            return deleteQuietly(resourceId, phase);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("等待公开资料详情缓存失效锁被中断，降级直接删除，resourceId={}", resourceId, ex);
            deleteQuietly(resourceId, phase + "降级");
            return false;
        } catch (RuntimeException ex) {
            log.warn("公开资料详情缓存{}获取锁失败，降级直接删除，resourceId={}", phase, resourceId, ex);
            deleteQuietly(resourceId, phase + "降级");
            return false;
        } finally {
            releaseResourceLock(resourceLock, resourceId, phase);
        }
    }

    /**
     * 调度失败不会清除绕过；已被同锁删除确认清除或被后续失效替换的批次会直接 no-op。
     */
    private void scheduleInvalidationRetry(
            long resourceId,
            Instant bypassUntil,
            Duration retryDelay,
            AtomicBoolean retryPlanComplete) {
        try {
            ScheduledFuture<?> scheduledFuture = taskScheduler.schedule(
                    () -> retryInvalidation(resourceId, bypassUntil, retryDelay, retryPlanComplete),
                    Instant.now().plus(retryDelay));
            if (scheduledFuture == null) {
                retryPlanComplete.set(false);
                log.warn(
                        "调度公开资料详情缓存失效重试未返回任务，绕过标记保留，resourceId={}，delayMs={}",
                        resourceId,
                        retryDelay.toMillis());
            }
        } catch (RuntimeException ex) {
            retryPlanComplete.set(false);
            log.warn(
                    "调度公开资料详情缓存失效重试失败，绕过标记保留，resourceId={}，delayMs={}",
                    resourceId,
                    retryDelay.toMillis(),
                    ex);
        }
    }

    private void retryInvalidation(
            long resourceId,
            Instant bypassUntil,
            Duration retryDelay,
            AtomicBoolean retryPlanComplete) {
        if (!bypassUntil.equals(bypassUntilByResource.get(resourceId))) {
            return;
        }
        boolean deleteConfirmed = deleteWithResourceLock(resourceId, "延迟" + retryDelay.toMillis() + "ms删除");
        if (deleteConfirmed && retryPlanComplete.get()) {
            clearBypass(resourceId, bypassUntil);
        }
    }

    /**
     * 绕过到期后按当前访问懒清理；有效期内禁止当前实例读取和回填可能仍残留的旧缓存。
     */
    private boolean isCacheBypassed(long resourceId) {
        Instant bypassUntil = bypassUntilByResource.get(resourceId);
        if (bypassUntil == null) {
            return false;
        }
        if (bypassUntil.isAfter(Instant.now())) {
            return true;
        }
        bypassUntilByResource.remove(resourceId, bypassUntil);
        return false;
    }

    private void clearBypass(long resourceId, Instant bypassUntil) {
        bypassUntilByResource.remove(resourceId, bypassUntil);
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
