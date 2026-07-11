package com.john.campus.service.impl;

import com.john.campus.common.ErrorCode;
import com.john.campus.exception.BusinessException;
import com.john.campus.mapper.ResourceMapper;
import com.john.campus.service.DownloadDeltaPersistenceService;
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

    public DownloadDeltaPersistenceServiceImpl(ResourceMapper resourceMapper) {
        this.resourceMapper = resourceMapper;
    }

    /**
     * 批量落库时任一资料不存在都会使整个事务回滚，外层保留 syncing 批次以供下次重试，避免静默丢失下载增量。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void persistDownloadDeltas(Map<Long, Long> resourceDeltas) {
        for (Map.Entry<Long, Long> entry : resourceDeltas.entrySet()) {
            Long resourceId = entry.getKey();
            Long delta = entry.getValue();
            if (resourceMapper.incrementDownloadCount(resourceId, delta) != 1) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "同步下载增量失败，资料不存在: " + resourceId);
            }
        }
    }
}
