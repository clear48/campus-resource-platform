package com.john.campus.task;

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

    @Test
    void shouldDelegateToDownloadDeltaSyncService() {
        RankingSyncTask task = new RankingSyncTask(downloadDeltaSyncService);

        task.syncDownloadDeltas();

        verify(downloadDeltaSyncService).syncDownloadDeltas();
    }
}
