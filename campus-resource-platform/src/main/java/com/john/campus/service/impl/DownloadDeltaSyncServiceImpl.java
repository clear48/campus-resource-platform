package com.john.campus.service.impl;

import com.john.campus.common.RedisKeyConstants;
import com.john.campus.service.DownloadDeltaPersistenceService;
import com.john.campus.service.DownloadDeltaSyncService;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * 下载增量同步编排：通过锁和 Hash 批次隔离保证不丢失并发写入，MySQL 失败时保留批次供后续任务重试。
 */
@Service
public class DownloadDeltaSyncServiceImpl implements DownloadDeltaSyncService {

    private static final Logger log = LoggerFactory.getLogger(DownloadDeltaSyncServiceImpl.class);

    /**
     * 使用固定 active 批次名而非一次性 UUID：任务异常后的 Hash 可被下一轮明确定位并继续处理。
     */
    private static final String ACTIVE_BATCH_ID = "active";

    /**
     * 释放锁必须比对 owner token；锁超时后若被其他实例重新获取，旧实例不能误删新锁。
     */
    private static final RedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    /** Redis 下载增量和同步锁的访问入口。 */
    private final StringRedisTemplate stringRedisTemplate;
    /** 独立事务 Bean，确保数据库落库通过 Spring 代理开启和提交事务。 */
    private final DownloadDeltaPersistenceService downloadDeltaPersistenceService;
    /** 单轮最多落库的资料数量，控制事务时长；剩余成员保留在 syncing 批次等待下一轮。 */
    private final int maxBatchSize;
    /** 分布式锁 TTL 必须覆盖一轮受限批次的预期最大执行时间。 */
    private final Duration lockTtl;

    public DownloadDeltaSyncServiceImpl(
            StringRedisTemplate stringRedisTemplate,
            DownloadDeltaPersistenceService downloadDeltaPersistenceService,
            @Value("${rank.sync.download-delta.max-batch-size:500}") int maxBatchSize,
            @Value("${rank.sync.download-delta.lock-ttl-seconds:300}") long lockTtlSeconds) {
        if (maxBatchSize < 1 || lockTtlSeconds < 1) {
            throw new IllegalArgumentException("下载增量同步批次大小和锁 TTL 必须大于 0");
        }
        this.stringRedisTemplate = stringRedisTemplate;
        this.downloadDeltaPersistenceService = downloadDeltaPersistenceService;
        this.maxBatchSize = maxBatchSize;
        this.lockTtl = Duration.ofSeconds(lockTtlSeconds);
    }

    /**
     * 一轮同步流程：获取锁 -> 隔离旧 delta -> 事务落库 -> 删除已确认 field -> 安全释放锁。
     * 数据库失败时不删除 syncing 批次，因此新下载继续写入新的 delta Hash，旧批次可在下一轮重试。
     */
    @Override
    public void syncDownloadDeltas() {
        String ownerToken = UUID.randomUUID().toString();
        if (!tryAcquireLock(ownerToken)) {
            log.debug("跳过下载增量同步，其他实例正在处理");
            return;
        }

        try {
            String syncingKey = isolateOrResumeBatch();
            if (syncingKey == null) {
                return;
            }

            Map<String, Long> fieldDeltas = readValidBatchDeltas(syncingKey);
            if (fieldDeltas.isEmpty()) {
                return;
            }

            Map<Long, Long> resourceDeltas = new LinkedHashMap<>();
            fieldDeltas.forEach((resourceId, delta) -> resourceDeltas.put(Long.valueOf(resourceId), delta));
            // 该调用进入独立 Spring Bean 的 @Transactional 方法；抛错时同步批次保留，绝不提前确认。
            downloadDeltaPersistenceService.persistDownloadDeltas(resourceDeltas);
            confirmPersistedFields(syncingKey, fieldDeltas.keySet());
        } catch (RuntimeException ex) {
            // Redis 与 MySQL 没有分布式事务；这里选择至少一次语义，失败批次留存优先保证不丢计数。
            log.warn("下载增量同步失败，syncing 批次将保留以便重试", ex);
        } finally {
            releaseLock(ownerToken);
        }
    }

    /**
     * 优先恢复上次失败留下的 active 批次；没有遗留批次时用 RENAME 原子隔离当前 delta，新写入会自动进入新 Hash。
     */
    private String isolateOrResumeBatch() {
        String syncingKey = RedisKeyConstants.downloadDeltaSyncing(ACTIVE_BATCH_ID);
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(syncingKey))) {
            return syncingKey;
        }
        if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(RedisKeyConstants.DOWNLOAD_DELTA))) {
            return null;
        }

        // RENAME 是 Redis 单命令原子操作，隔离完成后并发 HINCRBY 不会落入当前正在处理的批次。
        stringRedisTemplate.rename(RedisKeyConstants.DOWNLOAD_DELTA, syncingKey);
        return syncingKey;
    }

    /**
     * 解析 Hash 中合法的正资源 ID 与正增量，并限制本轮数量；无法解析的脏 field 会被删除，避免永久阻塞批次。
     */
    private Map<String, Long> readValidBatchDeltas(String syncingKey) {
        HashOperations<String, Object, Object> hashOperations = stringRedisTemplate.opsForHash();
        Map<Object, Object> rawEntries = hashOperations.entries(syncingKey);
        Map<String, Long> validDeltas = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> entry : rawEntries.entrySet()) {
            String resourceId = String.valueOf(entry.getKey());
            Long delta = parsePositiveLong(entry.getValue());
            if (!isPositiveResourceId(resourceId) || delta == null) {
                log.warn("丢弃无法同步的下载增量 field: key={}, field={}, value={}", syncingKey, entry.getKey(), entry.getValue());
                hashOperations.delete(syncingKey, entry.getKey());
                continue;
            }
            validDeltas.put(resourceId, delta);
            if (validDeltas.size() == maxBatchSize) {
                break;
            }
        }
        return validDeltas;
    }

    /** 仅在 MySQL 事务提交成功后删除已落库字段；未处理的成员仍保留在 active 批次中。 */
    private void confirmPersistedFields(String syncingKey, java.util.Set<String> resourceIds) {
        if (resourceIds.isEmpty()) {
            return;
        }
        stringRedisTemplate.opsForHash().delete(syncingKey, resourceIds.toArray());
    }

    /** SET NX EX 获取锁；锁竞争或 Redis 异常均不能让定时任务并发落库。 */
    private boolean tryAcquireLock(String ownerToken) {
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(
                    RedisKeyConstants.DOWNLOAD_DELTA_SYNC_LOCK,
                    ownerToken,
                    lockTtl));
        } catch (RuntimeException ex) {
            log.warn("获取下载增量同步锁失败，本轮不执行", ex);
            return false;
        }
    }

    /** 使用 Lua 比较并删除锁，保证过期锁被其他实例接管后不会被当前实例误释放。 */
    private void releaseLock(String ownerToken) {
        try {
            stringRedisTemplate.execute(
                    RELEASE_LOCK_SCRIPT,
                    List.of(RedisKeyConstants.DOWNLOAD_DELTA_SYNC_LOCK),
                    ownerToken);
        } catch (RuntimeException ex) {
            // 释放失败只会等待 TTL 自动过期；不覆盖主同步结果，也不尝试无条件 DEL。
            log.warn("释放下载增量同步锁失败，将等待 TTL 自动过期", ex);
        }
    }

    private boolean isPositiveResourceId(String value) {
        try {
            return Long.parseLong(value) > 0;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private Long parsePositiveLong(Object value) {
        try {
            long parsedValue = Long.parseLong(String.valueOf(value));
            return parsedValue > 0 ? parsedValue : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
