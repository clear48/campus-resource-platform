package com.john.campus.task;

import com.john.campus.service.DownloadDeltaSyncService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 排行榜同步任务触发器：默认按配置唤起 Service，不持有锁、事务或 Redis 批次处理逻辑；测试或运维可通过开关停用。
 */
@Component
@ConditionalOnProperty(
        name = "rank.sync.download-delta.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class RankingSyncTask {

    /** 下载增量同步业务由独立 Service 承担，确保事务通过 Spring 代理生效。 */
    private final DownloadDeltaSyncService downloadDeltaSyncService;
    /** 调度监控只记录入口耗时和未捕获异常，不参与下载增量的锁、批次或事务处理。 */
    private final RankingTaskExecutionMonitor taskExecutionMonitor;

    public RankingSyncTask(
            DownloadDeltaSyncService downloadDeltaSyncService,
            RankingTaskExecutionMonitor taskExecutionMonitor) {
        this.downloadDeltaSyncService = downloadDeltaSyncService;
        this.taskExecutionMonitor = taskExecutionMonitor;
    }

    /**
     * 固定延迟表示上一次执行结束后再等待，避免单实例因同步耗时发生本地任务重叠。
     */
    @Scheduled(fixedDelayString = "${rank.sync.download-delta.fixed-delay-ms:60000}")
    public void syncDownloadDeltas() {
        taskExecutionMonitor.execute(
                RankingTaskExecutionMonitor.DOWNLOAD_DELTA_SYNC,
                downloadDeltaSyncService::syncDownloadDeltas);
    }
}
