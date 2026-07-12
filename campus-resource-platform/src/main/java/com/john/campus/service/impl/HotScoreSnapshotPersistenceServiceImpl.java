package com.john.campus.service.impl;

import com.john.campus.mapper.ResourceMapper;
import com.john.campus.service.HotScoreSnapshotPersistenceService;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 总榜热度分快照持久化：事务只覆盖 MySQL，Redis all 榜仍是实时来源且不因快照失败被回滚或删除。
 */
@Service
public class HotScoreSnapshotPersistenceServiceImpl implements HotScoreSnapshotPersistenceService {

    /** 资料热度快照更新入口，SQL 固定携带 status = 1 条件。 */
    private final ResourceMapper resourceMapper;

    public HotScoreSnapshotPersistenceServiceImpl(ResourceMapper resourceMapper) {
        this.resourceMapper = resourceMapper;
    }

    /**
     * 一个批次内任一数据库异常都会回滚，下一轮快照重新读取 Redis all 榜；更新行数为 0 表示资料已下架或删除，安全跳过。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void persistApprovedHotScores(Map<Long, BigDecimal> hotScores) {
        for (Map.Entry<Long, BigDecimal> entry : hotScores.entrySet()) {
            resourceMapper.updateApprovedHotScore(entry.getKey(), entry.getValue());
        }
    }
}
