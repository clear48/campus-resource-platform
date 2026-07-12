package com.john.campus.service;

/**
 * 管理员排行榜运维入口：将权限校验与实际重建流程隔离，避免 Controller 直接调用底层维护服务。
 */
public interface AdminRankingService {

    /** 管理员按当前 MySQL 统计快照手动重建资源热度总榜。 */
    void rebuildAllHotRanking();
}
