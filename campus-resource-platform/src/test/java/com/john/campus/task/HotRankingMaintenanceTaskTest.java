package com.john.campus.task;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import com.john.campus.service.HotRankingMaintenanceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 验证定时任务只负责触发总榜维护 Service，不承载锁、Redis 或事务实现。 */
@ExtendWith(MockitoExtension.class)
class HotRankingMaintenanceTaskTest {

    @Mock
    private HotRankingMaintenanceService hotRankingMaintenanceService;
    @Mock
    private RankingTaskExecutionMonitor taskExecutionMonitor;

    @Test
    void shouldDelegateRebuildAndSnapshotToMaintenanceService() {
        // 监控器仅包裹调度入口；测试中执行传入 Runnable，保证原有两个维护委派仍会触发。
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(taskExecutionMonitor).execute(any(String.class), any(Runnable.class));
        HotRankingMaintenanceTask task = new HotRankingMaintenanceTask(hotRankingMaintenanceService, taskExecutionMonitor);

        task.rebuildAllHotRankingIfMissing();
        task.snapshotAllHotScores();

        verify(taskExecutionMonitor).execute(eq(RankingTaskExecutionMonitor.ALL_RANKING_REBUILD), any(Runnable.class));
        verify(taskExecutionMonitor).execute(eq(RankingTaskExecutionMonitor.ALL_RANKING_SNAPSHOT), any(Runnable.class));
        verify(hotRankingMaintenanceService).rebuildAllHotRankingIfMissing();
        verify(hotRankingMaintenanceService).snapshotAllHotScores();
    }
}
