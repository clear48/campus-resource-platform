package com.john.campus.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.john.campus.common.RedisKeyConstants;
import com.john.campus.entity.Resource;
import com.john.campus.service.impl.ResourceDetailCacheServiceImpl;
import com.john.campus.vo.ResourceDetailVO;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.TaskScheduler;

/**
 * 公开资料详情缓存单元测试，重点验证共享快照边界、坏值自愈和 Redis 故障降级。
 */
@ExtendWith(MockitoExtension.class)
class ResourceDetailCacheServiceImplTest {

    private static final long RESOURCE_ID = 100L;
    private static final String CACHE_KEY = "crp:cache:resource:detail:100";

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private TaskScheduler taskScheduler;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock resourceLock;

    private ObjectMapper objectMapper;
    private ResourceDetailCacheService cacheService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        cacheService = new ResourceDetailCacheServiceImpl(stringRedisTemplate, objectMapper, taskScheduler);
    }

    @AfterEach
    void clearInterruptedFlag() {
        // 中断降级测试会恢复中断标记，必须清理以免污染同线程执行的后续用例。
        Thread.interrupted();
    }

    @Test
    void getOrLoadShouldReturnFirstCacheHitWithoutAcquiringLock() throws Exception {
        ResourceDetailVO cached = buildDetail(RESOURCE_ID, Resource.STATUS_APPROVED, null);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CACHE_KEY)).thenReturn(objectMapper.writeValueAsString(cached));
        AtomicInteger loaderCalls = new AtomicInteger();

        ResourceDetailVO result = lockedCacheService().getOrLoad(RESOURCE_ID, () -> {
            loaderCalls.incrementAndGet();
            return buildDetail(RESOURCE_ID, Resource.STATUS_APPROVED, null);
        });

        assertThat(result).isEqualTo(cached);
        assertThat(loaderCalls).hasValue(0);
        verifyNoInteractions(redissonClient, resourceLock);
    }

    @Test
    void getOrLoadShouldUseSecondCacheHitWithoutCallingLoader() throws Exception {
        ResourceDetailVO cachedByOtherThread = buildDetail(RESOURCE_ID, Resource.STATUS_APPROVED, null);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CACHE_KEY))
                .thenReturn(null)
                .thenReturn(objectMapper.writeValueAsString(cachedByOtherThread));
        when(redissonClient.getLock("crp:lock:cache:resource:detail:100")).thenReturn(resourceLock);
        when(resourceLock.tryLock(2_000L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(resourceLock.isHeldByCurrentThread()).thenReturn(true);
        AtomicInteger loaderCalls = new AtomicInteger();

        ResourceDetailVO result = lockedCacheService().getOrLoad(RESOURCE_ID, () -> {
            loaderCalls.incrementAndGet();
            return buildDetail(RESOURCE_ID, Resource.STATUS_APPROVED, null);
        });

        assertThat(result).isEqualTo(cachedByOtherThread);
        assertThat(loaderCalls).hasValue(0);
        verify(resourceLock).unlock();
        verify(valueOperations, never()).set(any(), any(), any(Duration.class));
    }

    @Test
    void getOrLoadShouldLoadOnceBackfillWhileLockedAndUnlock() throws Exception {
        ResourceDetailVO loaded = buildDetail(RESOURCE_ID, Resource.STATUS_APPROVED, true);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        when(redissonClient.getLock("crp:lock:cache:resource:detail:100")).thenReturn(resourceLock);
        when(resourceLock.tryLock(2_000L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(resourceLock.isHeldByCurrentThread()).thenReturn(true);
        AtomicInteger loaderCalls = new AtomicInteger();

        ResourceDetailVO result = lockedCacheService().getOrLoad(RESOURCE_ID, () -> {
            loaderCalls.incrementAndGet();
            return loaded;
        });

        assertThat(result).isSameAs(loaded);
        assertThat(loaderCalls).hasValue(1);
        InOrder inOrder = org.mockito.Mockito.inOrder(resourceLock, valueOperations);
        inOrder.verify(resourceLock).tryLock(2_000L, TimeUnit.MILLISECONDS);
        inOrder.verify(valueOperations).set(eq(CACHE_KEY), any(String.class), any(Duration.class));
        inOrder.verify(resourceLock).isHeldByCurrentThread();
        inOrder.verify(resourceLock).unlock();
    }

    @Test
    void getOrLoadShouldUnlockWhenLoaderThrows() throws Exception {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        when(redissonClient.getLock("crp:lock:cache:resource:detail:100")).thenReturn(resourceLock);
        when(resourceLock.tryLock(2_000L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(resourceLock.isHeldByCurrentThread()).thenReturn(true);

        assertThatThrownBy(() -> lockedCacheService().getOrLoad(
                RESOURCE_ID, () -> { throw new IllegalArgumentException("loader failed"); }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("loader failed");
        verify(resourceLock).unlock();
        verify(valueOperations, never()).set(any(), any(), any(Duration.class));
    }

    @Test
    void lockTimeoutShouldLoadOnceWithoutBackfillOrUnlock() throws Exception {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        when(redissonClient.getLock("crp:lock:cache:resource:detail:100")).thenReturn(resourceLock);
        when(resourceLock.tryLock(2_000L, TimeUnit.MILLISECONDS)).thenReturn(false);
        AtomicInteger loaderCalls = new AtomicInteger();

        ResourceDetailVO result = lockedCacheService().getOrLoad(RESOURCE_ID, () -> {
            loaderCalls.incrementAndGet();
            return buildDetail(RESOURCE_ID, Resource.STATUS_APPROVED, null);
        });

        assertThat(result.resourceId()).isEqualTo(RESOURCE_ID);
        assertThat(loaderCalls).hasValue(1);
        verify(valueOperations, never()).set(any(), any(), any(Duration.class));
        verify(resourceLock, never()).unlock();
    }

    @Test
    void interruptedLockWaitShouldRestoreFlagAndLoadOnceWithoutBackfill() throws Exception {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        when(redissonClient.getLock("crp:lock:cache:resource:detail:100")).thenReturn(resourceLock);
        when(resourceLock.tryLock(2_000L, TimeUnit.MILLISECONDS)).thenThrow(new InterruptedException("interrupted"));
        AtomicInteger loaderCalls = new AtomicInteger();

        ResourceDetailVO result = lockedCacheService().getOrLoad(RESOURCE_ID, () -> {
            loaderCalls.incrementAndGet();
            return buildDetail(RESOURCE_ID, Resource.STATUS_APPROVED, null);
        });

        assertThat(result.resourceId()).isEqualTo(RESOURCE_ID);
        assertThat(loaderCalls).hasValue(1);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        verify(valueOperations, never()).set(any(), any(), any(Duration.class));
    }

    @Test
    void redissonFailureShouldLoadOnceWithoutBackfill() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        when(redissonClient.getLock("crp:lock:cache:resource:detail:100"))
                .thenThrow(new IllegalStateException("redisson unavailable"));
        AtomicInteger loaderCalls = new AtomicInteger();

        ResourceDetailVO result = lockedCacheService().getOrLoad(RESOURCE_ID, () -> {
            loaderCalls.incrementAndGet();
            return buildDetail(RESOURCE_ID, Resource.STATUS_APPROVED, null);
        });

        assertThat(result.resourceId()).isEqualTo(RESOURCE_ID);
        assertThat(loaderCalls).hasValue(1);
        verify(valueOperations, never()).set(any(), any(), any(Duration.class));
    }

    @Test
    void getPublicDetailShouldDeserializeValidCacheHit() throws Exception {
        ResourceDetailVO snapshot = buildDetail(RESOURCE_ID, Resource.STATUS_APPROVED, null);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CACHE_KEY)).thenReturn(objectMapper.writeValueAsString(snapshot));

        Optional<ResourceDetailVO> result = cacheService.getPublicDetail(RESOURCE_ID);

        assertThat(result).contains(snapshot);
        verify(stringRedisTemplate, never()).delete(any(String.class));
    }

    @Test
    void getPublicDetailShouldReturnMissWhenKeyDoesNotExist() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);

        assertThat(cacheService.getPublicDetail(RESOURCE_ID)).isEmpty();
        verify(stringRedisTemplate, never()).delete(any(String.class));
    }

    @Test
    void cachePublicDetailShouldRemoveUserStateAndUseJitteredTtl() throws Exception {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);

        cacheService.cachePublicDetail(buildDetail(RESOURCE_ID, Resource.STATUS_APPROVED, true));

        verify(valueOperations).set(eq(CACHE_KEY), jsonCaptor.capture(), ttlCaptor.capture());
        ResourceDetailVO cached = objectMapper.readValue(jsonCaptor.getValue(), ResourceDetailVO.class);
        assertThat(cached.resourceId()).isEqualTo(RESOURCE_ID);
        assertThat(cached.title()).isEqualTo("Java 并发笔记");
        // 公共缓存不能携带任一访问者的收藏状态。
        assertThat(cached.favorited()).isNull();
        assertThat(ttlCaptor.getValue().getSeconds()).isBetween(1800L, 2100L);
    }

    @Test
    void getPublicDetailShouldDeleteMalformedJsonAndReturnMiss() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CACHE_KEY)).thenReturn("{bad-json");

        assertThat(cacheService.getPublicDetail(RESOURCE_ID)).isEmpty();
        verify(stringRedisTemplate).delete(CACHE_KEY);
    }

    @Test
    void getPublicDetailShouldDeleteMismatchedIdAndReturnMiss() throws Exception {
        assertInvalidSnapshotIsDeleted(buildDetail(101L, Resource.STATUS_APPROVED, null));
    }

    @Test
    void getPublicDetailShouldDeleteNonApprovedSnapshotAndReturnMiss() throws Exception {
        assertInvalidSnapshotIsDeleted(buildDetail(RESOURCE_ID, Resource.STATUS_OFFLINE, null));
    }

    @Test
    void getPublicDetailShouldDeleteUserStatePollutionAndReturnMiss() throws Exception {
        assertInvalidSnapshotIsDeleted(buildDetail(RESOURCE_ID, Resource.STATUS_APPROVED, false));
    }

    @Test
    void redisReadAndDeleteFailuresShouldDegradeToCacheMiss() throws Exception {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CACHE_KEY))
                .thenThrow(new IllegalStateException("redis unavailable"))
                .thenReturn("{bad-json");

        assertThat(cacheService.getPublicDetail(RESOURCE_ID)).isEmpty();
        doThrow(new IllegalStateException("delete unavailable")).when(stringRedisTemplate).delete(CACHE_KEY);
        assertThatCode(() -> assertThat(cacheService.getPublicDetail(RESOURCE_ID)).isEmpty())
                .doesNotThrowAnyException();
    }

    @Test
    void redisWriteFailureShouldNotAffectCaller() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        doThrow(new IllegalStateException("redis unavailable"))
                .when(valueOperations).set(eq(CACHE_KEY), any(String.class), any(Duration.class));

        assertThatCode(() -> cacheService.cachePublicDetail(
                buildDetail(RESOURCE_ID, Resource.STATUS_APPROVED, null)))
                .doesNotThrowAnyException();
    }

    @Test
    void invalidateWithoutRedissonShouldScheduleThreeRetriesAtConfiguredDelays() {
        Instant before = Instant.now();
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Instant> timeCaptor = ArgumentCaptor.forClass(Instant.class);
        when(taskScheduler.schedule(taskCaptor.capture(), timeCaptor.capture()))
                .thenReturn(org.mockito.Mockito.mock(ScheduledFuture.class));
        when(stringRedisTemplate.delete(CACHE_KEY)).thenReturn(false);

        cacheService.invalidateWithDelay(RESOURCE_ID);

        verify(stringRedisTemplate).delete(CACHE_KEY);
        verify(taskScheduler, times(3)).schedule(any(Runnable.class), any(Instant.class));
        assertThat(timeCaptor.getAllValues())
                .extracting(instant -> Duration.between(before, instant).toMillis())
                .allSatisfy(delay -> assertThat(delay).isBetween(450L, 5_200L));
        assertThat(timeCaptor.getAllValues().get(0).toEpochMilli() - before.toEpochMilli()).isBetween(450L, 700L);
        assertThat(timeCaptor.getAllValues().get(1).toEpochMilli() - before.toEpochMilli()).isBetween(1_900L, 2_200L);
        assertThat(timeCaptor.getAllValues().get(2).toEpochMilli() - before.toEpochMilli()).isBetween(4_900L, 5_200L);
    }

    @Test
    void schedulingFailureShouldNotAffectImmediateInvalidation() {
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenThrow(new IllegalStateException("scheduler unavailable"));

        assertThatCode(() -> cacheService.invalidateWithDelay(RESOURCE_ID)).doesNotThrowAnyException();
        verify(stringRedisTemplate).delete(CACHE_KEY);
        verify(taskScheduler, times(3)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void immediateLockedDeleteShouldScheduleThreeRetriesAndClearBypassWhenDeleteReturnsFalse() throws Exception {
        when(redissonClient.getLock("crp:lock:cache:resource:detail:100")).thenReturn(resourceLock);
        when(resourceLock.tryLock(2_000L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(resourceLock.isHeldByCurrentThread()).thenReturn(true);
        when(stringRedisTemplate.delete(CACHE_KEY)).thenReturn(false);
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenReturn(org.mockito.Mockito.mock(ScheduledFuture.class));
        ResourceDetailCacheService service = lockedCacheService();

        service.invalidateWithDelay(RESOURCE_ID);

        InOrder inOrder = org.mockito.Mockito.inOrder(resourceLock, stringRedisTemplate);
        inOrder.verify(resourceLock).tryLock(2_000L, TimeUnit.MILLISECONDS);
        inOrder.verify(stringRedisTemplate).delete(CACHE_KEY);
        inOrder.verify(resourceLock).isHeldByCurrentThread();
        inOrder.verify(resourceLock).unlock();
        verify(taskScheduler, times(3)).schedule(any(Runnable.class), any(Instant.class));

        // Boolean false 表示 Key 原本不存在，但命令已成功，bypass 应在完整重试计划建立后清理。
        org.mockito.Mockito.clearInvocations(stringRedisTemplate, redissonClient, resourceLock);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CACHE_KEY)).thenReturn(null);
        when(resourceLock.tryLock(2_000L, TimeUnit.MILLISECONDS)).thenReturn(false);
        service.getOrLoad(RESOURCE_ID, () -> buildDetail(RESOURCE_ID, Resource.STATUS_APPROVED, null));
        verify(stringRedisTemplate).opsForValue();
        verify(redissonClient).getLock("crp:lock:cache:resource:detail:100");
    }

    @Test
    void lockTimeoutShouldKeepBypassAndExecuteAllThreeRetries() throws Exception {
        when(redissonClient.getLock("crp:lock:cache:resource:detail:100")).thenReturn(resourceLock);
        when(resourceLock.tryLock(2_000L, TimeUnit.MILLISECONDS)).thenReturn(false);
        when(stringRedisTemplate.delete(CACHE_KEY)).thenReturn(false);
        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        when(taskScheduler.schedule(tasks.capture(), any(Instant.class)))
                .thenReturn(org.mockito.Mockito.mock(ScheduledFuture.class));
        ResourceDetailCacheService service = lockedCacheService();

        service.invalidateWithDelay(RESOURCE_ID);
        tasks.getAllValues().forEach(Runnable::run);

        verify(stringRedisTemplate, times(4)).delete(CACHE_KEY);
        verify(resourceLock, times(4)).tryLock(2_000L, TimeUnit.MILLISECONDS);
        assertBypassLoadsWithoutInfrastructure(service);
    }

    @Test
    void lockFailureShouldKeepBypassAndExecuteAllThreeRetries() {
        when(redissonClient.getLock("crp:lock:cache:resource:detail:100"))
                .thenThrow(new IllegalStateException("redisson unavailable"));
        when(stringRedisTemplate.delete(CACHE_KEY)).thenReturn(false);
        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        when(taskScheduler.schedule(tasks.capture(), any(Instant.class)))
                .thenReturn(org.mockito.Mockito.mock(ScheduledFuture.class));
        ResourceDetailCacheService service = lockedCacheService();

        service.invalidateWithDelay(RESOURCE_ID);
        tasks.getAllValues().forEach(Runnable::run);

        verify(stringRedisTemplate, times(4)).delete(CACHE_KEY);
        verify(redissonClient, times(4)).getLock("crp:lock:cache:resource:detail:100");
        assertBypassLoadsWithoutInfrastructure(service);
    }

    @Test
    void successfulRetryShouldClearBypassAndMakeLaterTasksNoOp() throws Exception {
        when(redissonClient.getLock("crp:lock:cache:resource:detail:100")).thenReturn(resourceLock);
        when(resourceLock.tryLock(2_000L, TimeUnit.MILLISECONDS)).thenReturn(false, true);
        // 立即阶段未拿到锁，首次重试拿到锁后才允许释放。
        when(resourceLock.isHeldByCurrentThread()).thenReturn(false, true);
        // 降级删除与持锁删除都返回 false；后者仍代表 Redis 命令成功，应清理 bypass。
        when(stringRedisTemplate.delete(CACHE_KEY)).thenReturn(false);
        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        when(taskScheduler.schedule(tasks.capture(), any(Instant.class)))
                .thenReturn(org.mockito.Mockito.mock(ScheduledFuture.class));
        ResourceDetailCacheService service = lockedCacheService();

        service.invalidateWithDelay(RESOURCE_ID);
        tasks.getAllValues().get(0).run();
        tasks.getAllValues().get(1).run();
        tasks.getAllValues().get(2).run();

        verify(stringRedisTemplate, times(2)).delete(CACHE_KEY);
        verify(resourceLock, times(2)).tryLock(2_000L, TimeUnit.MILLISECONDS);
        verify(resourceLock).unlock();
    }

    @Test
    void nullScheduledFutureShouldKeepBypassEvenAfterImmediateDeleteSucceeds() throws Exception {
        when(redissonClient.getLock("crp:lock:cache:resource:detail:100")).thenReturn(resourceLock);
        when(resourceLock.tryLock(2_000L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(resourceLock.isHeldByCurrentThread()).thenReturn(true);
        when(stringRedisTemplate.delete(CACHE_KEY)).thenReturn(false);
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class))).thenReturn(null);
        ResourceDetailCacheService service = lockedCacheService();

        service.invalidateWithDelay(RESOURCE_ID);

        verify(taskScheduler, times(3)).schedule(any(Runnable.class), any(Instant.class));
        assertBypassLoadsWithoutInfrastructure(service);
    }

    @Test
    void schedulingExceptionShouldKeepBypassEvenAfterImmediateDeleteSucceeds() throws Exception {
        when(redissonClient.getLock("crp:lock:cache:resource:detail:100")).thenReturn(resourceLock);
        when(resourceLock.tryLock(2_000L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(resourceLock.isHeldByCurrentThread()).thenReturn(true);
        when(stringRedisTemplate.delete(CACHE_KEY)).thenReturn(false);
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenThrow(new IllegalStateException("scheduler unavailable"));
        ResourceDetailCacheService service = lockedCacheService();

        service.invalidateWithDelay(RESOURCE_ID);

        verify(taskScheduler, times(3)).schedule(any(Runnable.class), any(Instant.class));
        assertBypassLoadsWithoutInfrastructure(service);
    }

    private void assertInvalidSnapshotIsDeleted(ResourceDetailVO snapshot) throws Exception {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CACHE_KEY)).thenReturn(objectMapper.writeValueAsString(snapshot));

        assertThat(cacheService.getPublicDetail(RESOURCE_ID)).isEmpty();
        verify(stringRedisTemplate).delete(CACHE_KEY);
    }

    private ResourceDetailVO buildDetail(long resourceId, int status, Boolean favorited) {
        return new ResourceDetailVO(
                resourceId,
                "Java 并发笔记",
                "线程池与锁",
                10L,
                "计算机基础",
                "Java 程序设计",
                Resource.TYPE_NOTE,
                List.of("Java", "并发"),
                status,
                20L,
                5L,
                new BigDecimal("88.50"),
                LocalDateTime.of(2026, 8, 7, 10, 0),
                favorited);
    }

    private ResourceDetailCacheService lockedCacheService() {
        return new ResourceDetailCacheServiceImpl(
                stringRedisTemplate, objectMapper, taskScheduler, redissonClient);
    }

    private void assertBypassLoadsWithoutInfrastructure(ResourceDetailCacheService service) {
        org.mockito.Mockito.clearInvocations(stringRedisTemplate, valueOperations, redissonClient, resourceLock);
        AtomicInteger loaderCalls = new AtomicInteger();

        ResourceDetailVO result = service.getOrLoad(RESOURCE_ID, () -> {
            loaderCalls.incrementAndGet();
            return buildDetail(RESOURCE_ID, Resource.STATUS_APPROVED, null);
        });

        assertThat(result.resourceId()).isEqualTo(RESOURCE_ID);
        assertThat(loaderCalls).hasValue(1);
        verifyNoInteractions(stringRedisTemplate, valueOperations, redissonClient, resourceLock);
    }
}
