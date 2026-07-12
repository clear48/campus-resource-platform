package com.john.campus.task;

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

    @Test
    void shouldDelegateRebuildAndSnapshotToMaintenanceService() {
        HotRankingMaintenanceTask task = new HotRankingMaintenanceTask(hotRankingMaintenanceService);

        task.rebuildAllHotRankingIfMissing();
        task.snapshotAllHotScores();

        verify(hotRankingMaintenanceService).rebuildAllHotRankingIfMissing();
        verify(hotRankingMaintenanceService).snapshotAllHotScores();
    }
}
