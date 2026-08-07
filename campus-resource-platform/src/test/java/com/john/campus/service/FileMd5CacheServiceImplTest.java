package com.john.campus.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.john.campus.common.RedisKeyConstants;
import com.john.campus.service.impl.FileMd5CacheServiceImpl;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * MD5 三态缓存协议测试：验证正负值、坏值清理、TTL、NX 与 Redis 故障降级。
 */
@ExtendWith(MockitoExtension.class)
class FileMd5CacheServiceImplTest {

    private static final String MD5 = "0123456789abcdef0123456789abcdef";
    private static final long SIZE = 4096L;
    private static final String KEY = "crp:cache:file:md5:" + MD5 + ":" + SIZE;

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private FileMd5CacheService cacheService;

    @BeforeEach
    void setUp() {
        cacheService = new FileMd5CacheServiceImpl(stringRedisTemplate);
    }

    @Test
    void positiveNumericValueShouldReturnFound() {
        stubCachedValue("9223372036854775807");

        FileMd5CacheService.LookupResult result = cacheService.get(MD5, SIZE);

        assertThat(result.state()).isEqualTo(FileMd5CacheService.CacheState.FOUND);
        assertThat(result.fileId()).isEqualTo(Long.MAX_VALUE);
        verify(stringRedisTemplate, never()).delete(KEY);
    }

    @Test
    void notFoundSentinelShouldReturnNegativeHit() {
        stubCachedValue("NOT_FOUND");

        FileMd5CacheService.LookupResult result = cacheService.get(MD5, SIZE);

        assertThat(result.state()).isEqualTo(FileMd5CacheService.CacheState.NOT_FOUND);
        assertThat(result.fileId()).isNull();
        verify(stringRedisTemplate, never()).delete(KEY);
    }

    @ParameterizedTest
    @NullSource
    void nullValueShouldReturnAbsentWithoutDeleting(String cachedValue) {
        stubCachedValue(cachedValue);

        FileMd5CacheService.LookupResult result = cacheService.get(MD5, SIZE);

        assertThat(result.state()).isEqualTo(FileMd5CacheService.CacheState.ABSENT);
        assertThat(result.fileId()).isNull();
        verify(stringRedisTemplate, never()).delete(KEY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"illegal", "0", "-1", "9223372036854775808"})
    void invalidNonPositiveOrOverflowValueShouldDeleteAndReturnAbsent(String cachedValue) {
        stubCachedValue(cachedValue);

        FileMd5CacheService.LookupResult result = cacheService.get(MD5, SIZE);

        assertThat(result.state()).isEqualTo(FileMd5CacheService.CacheState.ABSENT);
        assertThat(result.fileId()).isNull();
        verify(stringRedisTemplate).delete(KEY);
    }

    @Test
    void getFailureShouldFailOpenAsAbsent() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(KEY)).thenThrow(new RuntimeException("redis unavailable"));

        FileMd5CacheService.LookupResult result = cacheService.get(MD5, SIZE);

        assertThat(result.state()).isEqualTo(FileMd5CacheService.CacheState.ABSENT);
        assertThat(result.fileId()).isNull();
        verify(stringRedisTemplate, never()).delete(KEY);
    }

    @Test
    void putFoundShouldUseOverwriteSetWithSixHourTtl() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        cacheService.putFound(MD5, SIZE, 123L);

        // 普通 SET 必须覆盖并发遗留的 NOT_FOUND，不能使用 NX。
        verify(valueOperations).set(KEY, "123", Duration.ofHours(6));
        verify(valueOperations, never()).setIfAbsent(KEY, "123", Duration.ofHours(6));
    }

    @Test
    void putNotFoundShouldUseSetIfAbsentWithFiveMinuteTtl() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        cacheService.putNotFound(MD5, SIZE);

        verify(valueOperations).setIfAbsent(KEY, "NOT_FOUND", Duration.ofMinutes(5));
    }

    @Test
    void evictShouldDeleteComputedKey() {
        cacheService.evict(MD5, SIZE);

        verify(stringRedisTemplate).delete(RedisKeyConstants.fileMd5Cache(MD5, SIZE));
    }

    @Test
    void writeAndDeleteFailuresShouldNotEscape() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        doThrow(new RuntimeException("set failed"))
                .when(valueOperations).set(KEY, "123", Duration.ofHours(6));
        when(valueOperations.setIfAbsent(KEY, "NOT_FOUND", Duration.ofMinutes(5)))
                .thenThrow(new RuntimeException("set nx failed"));
        when(stringRedisTemplate.delete(KEY)).thenThrow(new RuntimeException("delete failed"));

        assertThatCode(() -> cacheService.putFound(MD5, SIZE, 123L)).doesNotThrowAnyException();
        assertThatCode(() -> cacheService.putNotFound(MD5, SIZE)).doesNotThrowAnyException();
        assertThatCode(() -> cacheService.evict(MD5, SIZE)).doesNotThrowAnyException();
    }

    private void stubCachedValue(String value) {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(KEY)).thenReturn(value);
    }
}
