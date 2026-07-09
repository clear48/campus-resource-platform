package com.john.campus.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.john.campus.common.ErrorCode;
import com.john.campus.common.RedisKeyConstants;
import com.john.campus.exception.BusinessException;
import com.john.campus.service.impl.DownloadRateLimiterImpl;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 下载限流器单元测试：不连接真实 Redis，通过 mock 验证 Lua 调用参数、超限和失败关闭策略。
 */
@ExtendWith(MockitoExtension.class)
class DownloadRateLimiterTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    private DownloadRateLimiter downloadRateLimiter;

    @BeforeEach
    void setUp() {
        downloadRateLimiter = new DownloadRateLimiterImpl(stringRedisTemplate);
    }

    @Test
    void checkUserLimitShouldExecuteLuaWithUserLimitKey() {
        mockLimiterResult(1L);

        downloadRateLimiter.checkUserLimit(10001L);

        ArgumentCaptor<List<String>> keysCaptor = captureRedisKeys();
        verify(stringRedisTemplate).execute(
                anyRedisScript(),
                keysCaptor.capture(),
                any(),
                any(),
                eq("10"),
                any(),
                eq("120"));
        assertThat(keysCaptor.getValue()).containsExactly(RedisKeyConstants.downloadRateUser(10001L));
    }

    @Test
    void checkIpLimitShouldExecuteLuaWithIpLimitKey() {
        mockLimiterResult(1L);

        downloadRateLimiter.checkIpLimit(" 192.168.1.10 ");

        ArgumentCaptor<List<String>> keysCaptor = captureRedisKeys();
        verify(stringRedisTemplate).execute(
                anyRedisScript(),
                keysCaptor.capture(),
                any(),
                any(),
                eq("30"),
                any(),
                eq("120"));
        assertThat(keysCaptor.getValue()).containsExactly(RedisKeyConstants.downloadRateIp("192.168.1.10"));
    }

    @Test
    void checkDownloadLimitShouldCheckUserThenIp() {
        mockLimiterResult(1L);

        downloadRateLimiter.checkDownloadLimit(10001L, "192.168.1.10");

        verify(stringRedisTemplate).execute(
                anyRedisScript(),
                eq(List.of(RedisKeyConstants.downloadRateUser(10001L))),
                any(),
                any(),
                eq("10"),
                any(),
                eq("120"));
        verify(stringRedisTemplate).execute(
                anyRedisScript(),
                eq(List.of(RedisKeyConstants.downloadRateIp("192.168.1.10"))),
                any(),
                any(),
                eq("30"),
                any(),
                eq("120"));
    }

    @Test
    void checkUserLimitShouldThrowRateLimitedWhenLuaRejects() {
        mockLimiterResult(0L);

        assertThatThrownBy(() -> downloadRateLimiter.checkUserLimit(10001L))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.RATE_LIMITED.getCode());
    }

    @Test
    void checkUserLimitShouldFailClosedWhenRedisThrowsException() {
        mockLimiterFailure();

        assertThatThrownBy(() -> downloadRateLimiter.checkUserLimit(10001L))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.RATE_LIMITED.getCode());
    }

    @Test
    void checkUserLimitShouldRejectInvalidUserIdBeforeRedis() {
        assertThatThrownBy(() -> downloadRateLimiter.checkUserLimit(0L))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.PARAM_ERROR.getCode());

        verify(stringRedisTemplate, never()).execute(anyRedisScript(), anyList(), any(), any(), any(), any(), any());
    }

    @Test
    void checkUserLimitShouldUseSlidingWindowLuaCommands() {
        mockLimiterResult(1L);

        downloadRateLimiter.checkUserLimit(10001L);

        ArgumentCaptor<RedisScript<Long>> scriptCaptor = captureRedisScript();
        verify(stringRedisTemplate).execute(scriptCaptor.capture(), anyList(), any(), any(), any(), any(), any());
        assertThat(scriptCaptor.getValue().getScriptAsString())
                .contains("ZREMRANGEBYSCORE", "ZCARD", "ZADD", "EXPIRE");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void mockLimiterResult(Long result) {
        doReturn(result).when(stringRedisTemplate)
                .execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void mockLimiterFailure() {
        doThrow(new RuntimeException("redis unavailable")).when(stringRedisTemplate)
                .execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any());
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<String>> captureRedisKeys() {
        return ArgumentCaptor.forClass(List.class);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<RedisScript<Long>> captureRedisScript() {
        return ArgumentCaptor.forClass(RedisScript.class);
    }

    @SuppressWarnings("unchecked")
    private RedisScript<Long> anyRedisScript() {
        return any(RedisScript.class);
    }
}
