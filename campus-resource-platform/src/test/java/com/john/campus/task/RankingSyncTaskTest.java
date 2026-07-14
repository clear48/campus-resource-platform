package com.john.campus.task;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import com.john.campus.service.DownloadDeltaSyncService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 定时任务测试：任务类只负责触发同步 Service，锁、Redis 和事务逻辑不能进入调度层。
 */
@ExtendWith(MockitoExtension.class)
class RankingSyncTaskTest {

    @Mock
    private DownloadDeltaSyncService downloadDeltaSyncService;
    @Mock
    private RankingTaskExecutionMonitor taskExecutionMonitor;

    @Test
    void shouldDelegateToDownloadDeltaSyncService() {
        // Mock 监控器需要主动执行 Runnable，才能同时验证调度观测与原有 Service 委派没有被替换。
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(taskExecutionMonitor).execute(eq(RankingTaskExecutionMonitor.DOWNLOAD_DELTA_SYNC), any(Runnable.class));
        RankingSyncTask task = new RankingSyncTask(downloadDeltaSyncService, taskExecutionMonitor);

        task.syncDownloadDeltas();

        verify(taskExecutionMonitor).execute(eq(RankingTaskExecutionMonitor.DOWNLOAD_DELTA_SYNC), any(Runnable.class));
        verify(downloadDeltaSyncService).syncDownloadDeltas();
    }
}
