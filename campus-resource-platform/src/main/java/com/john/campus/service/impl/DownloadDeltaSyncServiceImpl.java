package com.john.campus.service.impl;

import com.john.campus.common.RedisKeyConstants;
import com.john.campus.service.DownloadDeltaPersistenceService;
import com.john.campus.service.DownloadDeltaSyncService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 下载增量同步编排：通过 Redisson 看门狗锁和 Hash 批次隔离保证不丢失并发写入，MySQL 失败时保留批次供后续任务重试。
 */
@Service
public class DownloadDeltaSyncServiceImpl implements DownloadDeltaSyncService {

    private static final Logger log = LoggerFactory.getLogger(DownloadDeltaSyncServiceImpl.class);

    /**
     * 使用固定 active 批次名而非一次性 UUID：任务异常后的 Hash 可被下一轮明确定位并继续处理。
     */
    private static final String ACTIVE_BATCH_ID = "active";

    /** Redis 下载增量和同步锁的访问入口。 */
    private final StringRedisTemplate stringRedisTemplate;
    /** Redisson 提供可重入分布式锁；通过懒代理延迟到实际同步时创建，不传 leaseTime 时由看门狗自动续期。 */
    private final RedissonClient redissonClient;
    /** 独立事务 Bean，确保数据库落库通过 Spring 代理开启和提交事务。 */
    private final DownloadDeltaPersistenceService downloadDeltaPersistenceService;
    /** 单轮最多落库的资料数量，控制事务时长；剩余成员保留在 syncing 批次等待下一轮。 */
    private final int maxBatchSize;

    public DownloadDeltaSyncServiceImpl(
            StringRedisTemplate stringRedisTemplate,
            @Lazy RedissonClient redissonClient,
            DownloadDeltaPersistenceService downloadDeltaPersistenceService,
            @Value("${rank.sync.download-delta.max-batch-size:500}") int maxBatchSize) {
        if (maxBatchSize < 1) {
            throw new IllegalArgumentException("下载增量同步批次大小必须大于 0");
        }
        this.stringRedisTemplate = stringRedisTemplate;
        this.redissonClient = redissonClient;
        this.downloadDeltaPersistenceService = downloadDeltaPersistenceService;
        this.maxBatchSize = maxBatchSize;
    }

    /**
     * 一轮同步流程：获取锁 -> 隔离旧 delta -> 事务落库 -> 删除已确认 field -> 安全释放锁。
     * 数据库失败时不删除 syncing 批次，因此新下载继续写入新的 delta Hash，旧批次可在下一轮重试。
     */
    @Override
    public void syncDownloadDeltas() {
        RLock lock = redissonClient.getLock(RedisKeyConstants.DOWNLOAD_DELTA_SYNC_LOCK);
        if (!tryAcquireLock(lock)) {
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
            releaseLock(lock);
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

    /**
     * 使用不带 leaseTime 的 tryLock 获取锁：成功后 Redisson 看门狗会在当前客户端存活期间自动续期，避免长批次因固定 TTL 过期。
     */
    private boolean tryAcquireLock(RLock lock) {
        try {
            return lock.tryLock();
        } catch (RuntimeException ex) {
            log.warn("获取下载增量同步锁失败，本轮不执行", ex);
            return false;
        }
    }

    /** Redisson 只允许持锁线程解锁；先检查归属，避免锁已异常释放时抛出解锁异常覆盖同步结果。 */
    private void releaseLock(RLock lock) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (RuntimeException ex) {
            // 看门狗会在客户端失活后停止续期；释放失败不覆盖本轮同步结果。
            log.warn("释放下载增量同步锁失败，将由看门狗超时机制兜底", ex);
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
