package com.john.campus.task;

import com.john.campus.service.HotRankingMaintenanceService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 总榜维护任务触发器：只调度“缺失时重建”和“热度快照”，具体锁、分页和事务均由 Service 层负责。
 */
@Component
@ConditionalOnProperty(
        name = "rank.sync.hot-ranking.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class HotRankingMaintenanceTask {

    /** all 总榜重建和快照业务的统一入口。 */
    private final HotRankingMaintenanceService hotRankingMaintenanceService;
    /** 统一记录两个维护入口的耗时与未捕获异常，便于区分调度未触发和 Service 内部降级。 */
    private final RankingTaskExecutionMonitor taskExecutionMonitor;

    public HotRankingMaintenanceTask(
            HotRankingMaintenanceService hotRankingMaintenanceService,
            RankingTaskExecutionMonitor taskExecutionMonitor) {
        this.hotRankingMaintenanceService = hotRankingMaintenanceService;
        this.taskExecutionMonitor = taskExecutionMonitor;
    }

    /** all 榜 Redis 丢失时才重建，避免周期性覆盖实时热度增量。 */
    @Scheduled(fixedDelayString = "${rank.sync.hot-ranking.rebuild-fixed-delay-ms:300000}")
    public void rebuildAllHotRankingIfMissing() {
        taskExecutionMonitor.execute(
                RankingTaskExecutionMonitor.ALL_RANKING_REBUILD,
                hotRankingMaintenanceService::rebuildAllHotRankingIfMissing);
    }

    /** 将 Redis all 榜分批回写 MySQL，供 hot_score 排序和 Redis 故障降级使用。 */
    @Scheduled(fixedDelayString = "${rank.sync.hot-ranking.snapshot-fixed-delay-ms:300000}")
    public void snapshotAllHotScores() {
        taskExecutionMonitor.execute(
                RankingTaskExecutionMonitor.ALL_RANKING_SNAPSHOT,
                hotRankingMaintenanceService::snapshotAllHotScores);
    }
}
