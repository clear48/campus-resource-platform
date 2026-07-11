package com.john.campus.service;

import java.util.Map;

/**
 * 下载增量 MySQL 持久化边界：独立 Bean 承载事务，避免定时任务或同步编排类的自调用导致事务代理失效。
 */
public interface DownloadDeltaPersistenceService {

    /**
     * 在一个 MySQL 事务中原子累加本批次所有资料的下载量；任一资料更新失败时整体回滚。
     */
    void persistDownloadDeltas(Map<Long, Long> resourceDeltas);
}
