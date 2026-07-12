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

    public RankingSyncTask(DownloadDeltaSyncService downloadDeltaSyncService) {
        this.downloadDeltaSyncService = downloadDeltaSyncService;
    }

    /**
     * 固定延迟表示上一次执行结束后再等待，避免单实例因同步耗时发生本地任务重叠。
     */
    @Scheduled(fixedDelayString = "${rank.sync.download-delta.fixed-delay-ms:60000}")
    public void syncDownloadDeltas() {
        downloadDeltaSyncService.syncDownloadDeltas();
    }
}
