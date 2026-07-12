package com.john.campus.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import com.john.campus.common.ErrorCode;
import com.john.campus.common.LoginUser;
import com.john.campus.common.UserContextHolder;
import com.john.campus.exception.BusinessException;
import com.john.campus.service.impl.AdminRankingServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 管理员总榜重建服务测试：权限检查必须在调用 Redis 重建前完成。 */
@ExtendWith(MockitoExtension.class)
class AdminRankingServiceImplTest {

    @Mock
    private HotRankingMaintenanceService hotRankingMaintenanceService;

    @AfterEach
    void clearUserContext() {
        // 避免 ThreadLocal 在测试线程复用时污染下一条用例。
        UserContextHolder.clear();
    }

    @Test
    void adminShouldTriggerAllHotRankingRebuild() {
        UserContextHolder.set(new LoginUser(90001L, 2, "admin-jti"));
        AdminRankingService adminRankingService = new AdminRankingServiceImpl(hotRankingMaintenanceService);

        adminRankingService.rebuildAllHotRanking();

        verify(hotRankingMaintenanceService).rebuildAllHotRanking();
    }

    @Test
    void studentShouldBeForbiddenBeforeTriggeringRebuild() {
        UserContextHolder.set(new LoginUser(10001L, 1, "student-jti"));
        AdminRankingService adminRankingService = new AdminRankingServiceImpl(hotRankingMaintenanceService);

        assertThatThrownBy(adminRankingService::rebuildAllHotRanking)
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.FORBIDDEN.getCode());
    }

    @Test
    void anonymousCallerShouldBeRejectedBeforeTriggeringRebuild() {
        AdminRankingService adminRankingService = new AdminRankingServiceImpl(hotRankingMaintenanceService);

        assertThatThrownBy(adminRankingService::rebuildAllHotRanking)
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.UNAUTHORIZED.getCode());
    }
}
