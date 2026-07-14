package com.john.campus.service.impl;

import com.john.campus.common.ErrorCode;
import com.john.campus.exception.BusinessException;
import com.john.campus.mapper.DownloadDeltaSyncItemMapper;
import com.john.campus.mapper.ResourceMapper;
import com.john.campus.service.DownloadDeltaPersistenceService;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 下载增量 MySQL 落库实现：事务只包裹数据库更新，Redis 批次确认由外层同步编排在提交成功后执行。
 */
@Service
public class DownloadDeltaPersistenceServiceImpl implements DownloadDeltaPersistenceService {

    /**
     * 资料统计字段的原子累加入口；不采用“先查询再更新”，避免并发同步覆盖下载计数。
     */
    private final ResourceMapper resourceMapper;
    /** 同批次唯一记录是 Redis 确认失败时避免重复累加的 MySQL 兜底。 */
    private final DownloadDeltaSyncItemMapper downloadDeltaSyncItemMapper;

    public DownloadDeltaPersistenceServiceImpl(
            ResourceMapper resourceMapper,
            DownloadDeltaSyncItemMapper downloadDeltaSyncItemMapper) {
        this.resourceMapper = resourceMapper;
        this.downloadDeltaSyncItemMapper = downloadDeltaSyncItemMapper;
    }

    /**
     * 批量落库时任一资料不存在都会使整个事务回滚，外层保留 syncing 批次以供下次重试，避免静默丢失下载增量。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void persistDownloadDeltas(String batchId, Map<Long, Long> resourceDeltas) {
        for (Map.Entry<Long, Long> entry : resourceDeltas.entrySet()) {
            Long resourceId = entry.getKey();
            Long delta = entry.getValue();
            // 插入与下载量累加在同一事务内：事务回滚时两者都会回滚；提交后重试只会命中唯一键。
            if (downloadDeltaSyncItemMapper.insertIgnore(batchId, resourceId, delta) == 0) {
                validatePersistedDelta(batchId, resourceId, delta);
                continue;
            }
            if (resourceMapper.incrementDownloadCount(resourceId, delta) != 1) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "同步下载增量失败，资料不存在: " + resourceId);
            }
        }
    }

    /** Redis HDEL 成功后才标记确认，未确认记录必须保留以支持进程崩溃后的安全重试。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markDownloadDeltasConfirmed(String batchId, Collection<Long> resourceIds) {
        if (resourceIds == null || resourceIds.isEmpty()) {
            return;
        }
        downloadDeltaSyncItemMapper.markConfirmed(batchId, resourceIds, LocalDateTime.now());
    }

    /** 同一 UUID 批次的同一资料不允许出现不同增量，避免脏 Redis 数据被幂等逻辑静默吞掉。 */
    private void validatePersistedDelta(String batchId, Long resourceId, Long delta) {
        Long persistedDelta = downloadDeltaSyncItemMapper.selectDelta(batchId, resourceId);
        if (!delta.equals(persistedDelta)) {
            throw new BusinessException(ErrorCode.SERVER_ERROR,
                    "同步下载增量批次数据不一致: batchId=" + batchId + ", resourceId=" + resourceId);
        }
    }
}
