package com.john.campus.service;

/**
 * 热门资料总榜维护入口：负责 all 榜从 MySQL 统计快照重建，以及将 Redis 总榜分数持久化为 MySQL 热度快照。
 */
public interface HotRankingMaintenanceService {

    /**
     * 仅在 Redis all 总榜缺失时重建，避免定时任务反复覆盖实时行为增量。
     */
    void rebuildAllHotRankingIfMissing();

    /**
     * 从当前 APPROVED 资料统计字段完整重建 all 总榜；供后续受权限保护的内部管理任务复用。
     */
    void rebuildAllHotRanking();

    /**
     * 将 all 总榜的当前分数分批回写到 resource.hot_score，作为 MySQL 查询降级与搜索排序快照。
     */
    void snapshotAllHotScores();
}
