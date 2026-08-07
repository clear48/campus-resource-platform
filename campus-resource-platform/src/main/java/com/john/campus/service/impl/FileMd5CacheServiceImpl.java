package com.john.campus.service.impl;

import com.john.campus.common.RedisKeyConstants;
import com.john.campus.service.FileMd5CacheService;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 文件 MD5 缓存实现；Redis 仅负责预检加速，任何异常都按缓存无结论降级。
 */
@Service
public class FileMd5CacheServiceImpl implements FileMd5CacheService {

    private static final Logger log = LoggerFactory.getLogger(FileMd5CacheServiceImpl.class);

    /** 正缓存保留 6 小时，过期后重新以 MySQL 为准。 */
    private static final Duration FOUND_TTL = Duration.ofHours(6);
    /** 负缓存只保留 5 分钟，限制不存在文件的重复穿透时间。 */
    private static final Duration NOT_FOUND_TTL = Duration.ofMinutes(5);
    /** 固定非数字哨兵，协议上不会与正数 file_info.id 混淆。 */
    private static final String NOT_FOUND_SENTINEL = "NOT_FOUND";

    private final StringRedisTemplate stringRedisTemplate;

    public FileMd5CacheServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 兼容历史纯数字正值；空值是未命中，负哨兵表示数据库已确认不存在。
     * 非正数、溢出或其他协议外值会被最佳努力删除，避免坏值持续影响预检。
     */
    @Override
    public LookupResult get(String fileMd5, long fileSize) {
        String key = RedisKeyConstants.fileMd5Cache(fileMd5, fileSize);
        final String value;
        try {
            value = stringRedisTemplate.opsForValue().get(key);
        } catch (RuntimeException ex) {
            log.warn("读取文件 MD5 缓存失败，降级为查库: md5={}, size={}", fileMd5, fileSize, ex);
            return LookupResult.absent();
        }

        if (value == null) {
            return LookupResult.absent();
        }
        if (NOT_FOUND_SENTINEL.equals(value)) {
            return LookupResult.notFound();
        }

        try {
            long fileId = Long.parseLong(value);
            if (fileId > 0) {
                return LookupResult.found(fileId);
            }
        } catch (NumberFormatException ex) {
            // 协议外字符串或 long 溢出都属于坏值，统一走下方清理路径。
        }

        // 不把可能被污染的原值写入日志，避免超长内容或换行造成日志注入。
        log.warn("清理非法文件 MD5 缓存值: md5={}, size={}, valueLength={}",
                fileMd5, fileSize, value.length());
        evict(fileMd5, fileSize);
        return LookupResult.absent();
    }

    /**
     * 正缓存必须使用普通 SET，无条件覆盖旧负值，避免上传成功后仍被短期判为不存在。
     */
    @Override
    public void putFound(String fileMd5, long fileSize, Long fileId) {
        if (fileId == null || fileId <= 0) {
            // 缓存不能反向破坏数据库主流程，调用方数据异常时只告警并跳过写入。
            log.warn("跳过非法文件 ID 的 MD5 正缓存写入: md5={}, size={}, fileId={}", fileMd5, fileSize, fileId);
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(
                    RedisKeyConstants.fileMd5Cache(fileMd5, fileSize),
                    String.valueOf(fileId),
                    FOUND_TTL);
        } catch (RuntimeException ex) {
            log.warn("写入文件 MD5 正缓存失败: md5={}, size={}, fileId={}", fileMd5, fileSize, fileId, ex);
        }
    }

    /**
     * 负缓存使用 SET NX：若并发上传已经提交并写入正值，本次数据库旧读结果不能覆盖它。
     */
    @Override
    public void putNotFound(String fileMd5, long fileSize) {
        try {
            stringRedisTemplate.opsForValue().setIfAbsent(
                    RedisKeyConstants.fileMd5Cache(fileMd5, fileSize),
                    NOT_FOUND_SENTINEL,
                    NOT_FOUND_TTL);
        } catch (RuntimeException ex) {
            log.warn("写入文件 MD5 负缓存失败: md5={}, size={}", fileMd5, fileSize, ex);
        }
    }

    /**
     * 显式失效采用最佳努力语义；真实文件删除/恢复入口尚未实现，后续由生命周期流程调用。
     */
    @Override
    public void evict(String fileMd5, long fileSize) {
        try {
            stringRedisTemplate.delete(RedisKeyConstants.fileMd5Cache(fileMd5, fileSize));
        } catch (RuntimeException ex) {
            log.warn("删除文件 MD5 缓存失败: md5={}, size={}", fileMd5, fileSize, ex);
        }
    }
}
