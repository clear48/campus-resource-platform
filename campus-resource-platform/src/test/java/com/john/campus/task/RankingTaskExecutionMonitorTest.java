package com.john.campus.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 任务执行监控测试：验证正常完成、未捕获异常和最近快照的最小可观测性边界。
 */
class RankingTaskExecutionMonitorTest {

    @Test
    void shouldRecordCompletedTaskSnapshot() {
        RankingTaskExecutionMonitor monitor = new RankingTaskExecutionMonitor();

        monitor.execute(RankingTaskExecutionMonitor.DOWNLOAD_DELTA_SYNC, () -> {
            // 空任务用于验证监控自身，不引入 Redis、锁或数据库依赖。
        });

        Map<String, TaskExecutionSnapshot> snapshots = monitor.getLatestSnapshots();
        TaskExecutionSnapshot snapshot = snapshots.get(RankingTaskExecutionMonitor.DOWNLOAD_DELTA_SYNC);
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.status()).isEqualTo(TaskExecutionStatus.COMPLETED);
        assertThat(snapshot.startedAt()).isNotNull();
        assertThat(snapshot.finishedAt()).isNotNull();
        assertThat(snapshot.durationMillis()).isGreaterThanOrEqualTo(0L);
        assertThat(snapshot.failureType()).isNull();
    }

    @Test
    void shouldKeepFailureTypeAndRethrowUnexpectedTaskError() {
        RankingTaskExecutionMonitor monitor = new RankingTaskExecutionMonitor();

        assertThatThrownBy(() -> monitor.execute(RankingTaskExecutionMonitor.ALL_RANKING_SNAPSHOT,
                () -> {
                    throw new IllegalStateException("模拟未捕获异常");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("模拟未捕获异常");

        TaskExecutionSnapshot snapshot = monitor.getLatestSnapshots()
                .get(RankingTaskExecutionMonitor.ALL_RANKING_SNAPSHOT);
        assertThat(snapshot.status()).isEqualTo(TaskExecutionStatus.FAILED);
        assertThat(snapshot.failureType()).isEqualTo(IllegalStateException.class.getSimpleName());
        assertThat(snapshot.finishedAt()).isNotNull();
    }
}
