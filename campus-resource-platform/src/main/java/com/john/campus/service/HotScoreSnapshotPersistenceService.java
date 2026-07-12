package com.john.campus.service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 热度快照 MySQL 持久化边界：每个批次在独立事务中更新，避免定时任务类内部调用导致事务代理失效。
 */
public interface HotScoreSnapshotPersistenceService {

    /**
     * 原子持久化一个总榜批次；非 APPROVED 资料由 Mapper 状态条件跳过，不会被后台任务重新写回公开快照。
     */
    void persistApprovedHotScores(Map<Long, BigDecimal> hotScores);
}
