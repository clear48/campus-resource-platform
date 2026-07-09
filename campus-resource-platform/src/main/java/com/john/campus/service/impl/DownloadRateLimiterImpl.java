package com.john.campus.service.impl;

import com.john.campus.common.ErrorCode;
import com.john.campus.common.RedisKeyConstants;
import com.john.campus.exception.BusinessException;
import com.john.campus.service.DownloadRateLimiter;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Redis 下载限流实现：使用 ZSet 滑动窗口和 Lua 脚本保证清理、计数、写入、续期的原子性。
 */
@Service
public class DownloadRateLimiterImpl implements DownloadRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(DownloadRateLimiterImpl.class);

    /**
     * 限流窗口固定为 60 秒，与 docs/05-redis-design.md 的“每分钟最多 N 次”保持一致。
     */
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofSeconds(60);
    /**
     * 限流 Key 额外保留 60 秒，窗口自然滑出后仍能自动清理临时风控数据。
     */
    private static final Duration RATE_LIMIT_TTL = RATE_LIMIT_WINDOW.plusSeconds(60);
    private static final int USER_MAX_REQUESTS_PER_WINDOW = 10;
    private static final int IP_MAX_REQUESTS_PER_WINDOW = 30;
    private static final Long LUA_ALLOWED = 1L;

    /**
     * Lua 返回 1 表示放行，0 表示窗口内请求数已达到阈值。
     */
    private static final RedisScript<Long> RATE_LIMIT_SCRIPT = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local windowStart = tonumber(ARGV[2])
            local maxRequests = tonumber(ARGV[3])
            local requestId = ARGV[4]
            local ttlSeconds = tonumber(ARGV[5])

            redis.call('ZREMRANGEBYSCORE', key, 0, windowStart)
            local current = redis.call('ZCARD', key)
            if current >= maxRequests then
                redis.call('EXPIRE', key, ttlSeconds)
                return 0
            end

            redis.call('ZADD', key, now, requestId)
            redis.call('EXPIRE', key, ttlSeconds)
            return 1
            """, Long.class);

    /**
     * Redis 是下载防刷的关键依赖，本限流器采用失败关闭策略。
     */
    private final StringRedisTemplate stringRedisTemplate;

    public DownloadRateLimiterImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 下载入口统一先做用户限流，再做 IP 限流；用户维度先拦截可以减少共享 IP 的误伤。
     */
    @Override
    public void checkDownloadLimit(long userId, String ip) {
        checkUserLimit(userId);
        checkIpLimit(ip);
    }

    /**
     * 用户维度限制单账号下载频率，防止登录用户短时间内制造大量下载记录。
     */
    @Override
    public void checkUserLimit(long userId) {
        if (userId <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "用户 ID 不合法");
        }
        checkLimit(RedisKeyConstants.downloadRateUser(userId), USER_MAX_REQUESTS_PER_WINDOW, "用户下载过于频繁");
    }

    /**
     * IP 维度限制同一来源下载频率，作为账号维度之外的风控兜底。
     */
    @Override
    public void checkIpLimit(String ip) {
        if (!StringUtils.hasText(ip)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "客户端 IP 不能为空");
        }
        checkLimit(RedisKeyConstants.downloadRateIp(ip.trim()), IP_MAX_REQUESTS_PER_WINDOW, "当前 IP 下载过于频繁");
    }

    /**
     * 执行单个 Redis Key 的滑动窗口限流，所有 ARGV 都转成字符串以适配 StringRedisTemplate 序列化器。
     */
    private void checkLimit(String key, int maxRequests, String limitedMessage) {
        long nowMillis = System.currentTimeMillis();
        long windowStartMillis = nowMillis - RATE_LIMIT_WINDOW.toMillis();
        String requestId = nowMillis + ":" + UUID.randomUUID();
        Long allowed;
        try {
            allowed = stringRedisTemplate.execute(
                    RATE_LIMIT_SCRIPT,
                    List.of(key),
                    String.valueOf(nowMillis),
                    String.valueOf(windowStartMillis),
                    String.valueOf(maxRequests),
                    requestId,
                    String.valueOf(RATE_LIMIT_TTL.toSeconds()));
        } catch (RuntimeException ex) {
            // 下载限流是防刷闸口，Redis 异常时采用失败关闭，避免绕过限流继续放大下载量。
            log.warn("执行下载限流失败: key={}", key, ex);
            throw new BusinessException(ErrorCode.RATE_LIMITED, "下载限流服务暂不可用，请稍后重试");
        }

        if (!LUA_ALLOWED.equals(allowed)) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, limitedMessage);
        }
    }
}
