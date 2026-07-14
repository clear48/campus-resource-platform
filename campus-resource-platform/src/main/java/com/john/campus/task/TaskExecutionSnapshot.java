package com.john.campus.task;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 单个调度入口的最近执行快照。数据仅保存在当前应用实例内，重启后会重置，避免为演示型指标引入额外存储依赖。
 */
public record TaskExecutionSnapshot(
        UUID executionId,
        String taskName,
        TaskExecutionStatus status,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        Long durationMillis,
        String failureType) {
}
