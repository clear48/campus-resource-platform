package com.john.campus.service.impl;

import com.john.campus.common.RedisKeyConstants;
import com.john.campus.service.DownloadDeltaPersistenceService;
import com.john.campus.service.DownloadDeltaSyncService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 下载增量同步编排：通过 Redisson 看门狗锁和 Hash 批次隔离保证不丢失并发写入，MySQL 失败时保留批次供后续任务重试。
 */
@Service
public class DownloadDeltaSyncServiceImpl implements DownloadDeltaSyncService {

    private static final Logger log = LoggerFactory.getLogger(DownloadDeltaSyncServiceImpl.class);

    /** 用于识别升级前固定 syncing:active Hash；该历史批次必须人工核对后迁移，不能自动重试。 */
    private static final String LEGACY_ACTIVE_BATCH_ID = "legacy-active";

    /** Redis Hash field 与当前批次指针在同一 Lua 脚本内完成切换，防止 RENAME 后进程崩溃而无法发现 UUID。 */
    private static final DefaultRedisScript<Long> ISOLATE_DELTA_BATCH_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[2]) == 1 then
                return 0
            end
            if redis.call('EXISTS', KEYS[1]) == 0 then
                return 0
            end
            redis.call('RENAME', KEYS[1], KEYS[3])
            redis.call('SET', KEYS[2], ARGV[1])
            return 1
            """, Long.class);

    /**
     * 仅当 current 仍指向指定旧批次时才删除。看门狗异常后旧 worker 恢复时，不能误删新 worker 已写入的 UUID 指针。
     */
    private static final DefaultRedisScript<Long> CLEAR_CURRENT_BATCH_IF_MATCHES_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

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
            SyncingBatch syncingBatch = isolateOrResumeBatch();
            if (syncingBatch == null) {
                return;
            }

            Map<String, Long> fieldDeltas = readValidBatchDeltas(syncingBatch.syncingKey());
            if (fieldDeltas.isEmpty()) {
                return;
            }

            Map<Long, Long> resourceDeltas = new LinkedHashMap<>();
            fieldDeltas.forEach((resourceId, delta) -> resourceDeltas.put(Long.valueOf(resourceId), delta));
            // 该调用进入独立 Spring Bean 的 @Transactional 方法；抛错时同步批次保留，绝不提前确认。
            downloadDeltaPersistenceService.persistDownloadDeltas(syncingBatch.batchId(), resourceDeltas);
            confirmPersistedFields(syncingBatch, fieldDeltas.keySet());
        } catch (RuntimeException ex) {
            // Redis 与 MySQL 没有分布式事务；失败批次保留，后续以同一 UUID 的 MySQL 幂等记录安全重试。
            log.warn("下载增量同步失败，syncing 批次将保留以便重试", ex);
        } finally {
            releaseLock(lock);
        }
    }

    /**
     * 优先恢复 current 指向的 UUID 批次；升级前遗留 active Hash 只告警保留。新批次通过 Lua 原子 RENAME + SET 指针，避免崩溃后失去批次身份。
     */
    private SyncingBatch isolateOrResumeBatch() {
        SyncingBatch currentBatch = resumeCurrentBatch();
        if (currentBatch != null) {
            return currentBatch;
        }

        String legacySyncingKey = RedisKeyConstants.downloadDeltaSyncing("active");
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(legacySyncingKey))) {
            // 旧版本没有幂等明细，无法判断该 Hash 是否已在 MySQL 提交但尚未 HDEL；自动重试会重新打开重复累计窗口。
            log.error("检测到升级前遗留下载同步批次: key={}, batchId={}。请先按迁移文档核对后处理，当前版本不会自动累加该批次",
                    legacySyncingKey, LEGACY_ACTIVE_BATCH_ID);
            return null;
        }

        String batchId = UUID.randomUUID().toString();
        String syncingKey = RedisKeyConstants.downloadDeltaSyncing(batchId);
        Long isolated = stringRedisTemplate.execute(
                ISOLATE_DELTA_BATCH_SCRIPT,
                List.of(RedisKeyConstants.DOWNLOAD_DELTA, RedisKeyConstants.DOWNLOAD_DELTA_SYNCING_CURRENT, syncingKey),
                batchId);
        if (Long.valueOf(1L).equals(isolated)) {
            return new SyncingBatch(batchId, syncingKey, false);
        }
        return resumeCurrentBatch();
    }

    /** 当前指针存在但 Hash 已被成功确认时清理陈旧指针，避免其阻塞下一批下载增量。 */
    private SyncingBatch resumeCurrentBatch() {
        String batchId = stringRedisTemplate.opsForValue().get(RedisKeyConstants.DOWNLOAD_DELTA_SYNCING_CURRENT);
        if (!StringUtils.hasText(batchId)) {
            return null;
        }
        String syncingKey = RedisKeyConstants.downloadDeltaSyncing(batchId);
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(syncingKey))) {
            return new SyncingBatch(batchId, syncingKey, false);
        }
        clearCurrentPointerIfMatches(batchId);
        return null;
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

    /** 仅在 MySQL 事务提交成功后删除已落库字段；未处理的成员仍保留在原 UUID 批次中。 */
    private void confirmPersistedFields(SyncingBatch syncingBatch, java.util.Set<String> resourceIds) {
        if (resourceIds.isEmpty()) {
            return;
        }
        stringRedisTemplate.opsForHash().delete(syncingBatch.syncingKey(), resourceIds.toArray());
        // HDEL 正常返回后才标记确认；若此前进程崩溃，下一轮会用同一 batchId 命中唯一键而跳过重复累加。
        downloadDeltaPersistenceService.markDownloadDeltasConfirmed(
                syncingBatch.batchId(), resourceIds.stream().map(Long::valueOf).toList());
        if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(syncingBatch.syncingKey())) && !syncingBatch.legacy()) {
            clearCurrentPointerIfMatches(syncingBatch.batchId());
        }
    }

    /** Redis Lua 比较并删除 current 指针，避免失效旧锁恢复后删除新批次的指针。 */
    private void clearCurrentPointerIfMatches(String batchId) {
        stringRedisTemplate.execute(
                CLEAR_CURRENT_BATCH_IF_MATCHES_SCRIPT,
                List.of(RedisKeyConstants.DOWNLOAD_DELTA_SYNCING_CURRENT),
                batchId);
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

    /** UUID 批次、对应 Redis Hash 与 legacy 标记必须整体传递，防止确认阶段误删新批次指针。 */
    private record SyncingBatch(String batchId, String syncingKey, boolean legacy) {
    }
}
