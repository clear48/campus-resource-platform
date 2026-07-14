package com.john.campus.task;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 排行榜定时任务的进程内可观测性组件：统一输出耗时日志并保留每类任务的最近快照，不改变任务原有的锁、事务和降级语义。
 */
@Component
public class RankingTaskExecutionMonitor {

    public static final String DOWNLOAD_DELTA_SYNC = "rank.download-delta-sync";
    public static final String ALL_RANKING_REBUILD = "rank.all-ranking-rebuild";
    public static final String ALL_RANKING_SNAPSHOT = "rank.all-ranking-snapshot";

    private static final Logger log = LoggerFactory.getLogger(RankingTaskExecutionMonitor.class);

    /**
     * 当前实例内按任务名称保存最近一次执行结果。使用并发 Map，避免多个调度线程记录不同任务时互相阻塞。
     */
    private final ConcurrentMap<String, TaskExecutionSnapshot> latestSnapshots = new ConcurrentHashMap<>();

    /**
     * 执行调度委派并记录最近快照。若 Service 将可恢复问题自行降级并正常返回，本方法记录 COMPLETED，详细原因仍以 Service 日志为准。
     */
    public void execute(String taskName, Runnable task) {
        if (!StringUtils.hasText(taskName)) {
            throw new IllegalArgumentException("任务名称不能为空");
        }
        Objects.requireNonNull(task, "任务委派不能为空");

        UUID executionId = UUID.randomUUID();
        LocalDateTime startedAt = LocalDateTime.now();
        latestSnapshots.put(taskName, new TaskExecutionSnapshot(
                executionId, taskName, TaskExecutionStatus.RUNNING, startedAt, null, null, null));
        long startedAtNanos = System.nanoTime();

        try {
            task.run();
            long durationMillis = Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
            completeCurrentExecution(taskName, executionId, startedAt, durationMillis, TaskExecutionStatus.COMPLETED, null);
            log.info("排行榜定时任务执行完成: taskName={}, durationMs={}", taskName, durationMillis);
        } catch (RuntimeException ex) {
            long durationMillis = Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
            // 快照仅记录异常类型，避免把数据库连接串或业务敏感字段复制到长期可见的运行状态中。
            completeCurrentExecution(taskName, executionId, startedAt, durationMillis, TaskExecutionStatus.FAILED,
                    ex.getClass().getSimpleName());
            log.error("排行榜定时任务执行失败: taskName={}, durationMs={}", taskName, durationMillis, ex);
            throw ex;
        }
    }

    /**
     * 返回当前实例最近一次任务快照的不可变副本。后续如增加管理员查询接口，可直接复用该方法而不改变调度逻辑。
     */
    public Map<String, TaskExecutionSnapshot> getLatestSnapshots() {
        return Map.copyOf(latestSnapshots);
    }

    /**
     * 仅允许本次执行覆盖自己的 RUNNING 快照，避免极端情况下较慢的旧执行完成后反向覆盖新的执行结果。
     */
    private void completeCurrentExecution(
            String taskName,
            UUID executionId,
            LocalDateTime startedAt,
            long durationMillis,
            TaskExecutionStatus status,
            String failureType) {
        LocalDateTime finishedAt = LocalDateTime.now();
        latestSnapshots.computeIfPresent(taskName, (ignored, currentSnapshot) -> {
            if (!currentSnapshot.executionId().equals(executionId)) {
                return currentSnapshot;
            }
            return new TaskExecutionSnapshot(
                    executionId, taskName, status, startedAt, finishedAt, durationMillis, failureType);
        });
    }
}
