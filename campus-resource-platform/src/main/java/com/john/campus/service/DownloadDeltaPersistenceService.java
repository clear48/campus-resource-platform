package com.john.campus.service;

import java.util.Map;

/**
 * 下载增量 MySQL 持久化边界：独立 Bean 承载事务，避免定时任务或同步编排类的自调用导致事务代理失效。
 */
public interface DownloadDeltaPersistenceService {

    /**
     * 在一个 MySQL 事务中先写入批次幂等记录，再原子累加下载量；任一资料更新失败时整体回滚。
     */
    void persistDownloadDeltas(String batchId, Map<Long, Long> resourceDeltas);

    /** Redis 成功确认后记录确认时间，保留幂等明细供重试识别和后续审计清理。 */
    void markDownloadDeltasConfirmed(String batchId, java.util.Collection<Long> resourceIds);
}
