package com.john.campus.service.impl;

import com.john.campus.common.ErrorCode;
import com.john.campus.common.LoginUser;
import com.john.campus.common.UserContextHolder;
import com.john.campus.exception.BusinessException;
import com.john.campus.service.AdminRankingService;
import com.john.campus.service.HotRankingMaintenanceService;
import org.springframework.stereotype.Service;

/**
 * 管理员手动重建总榜的业务边界：只负责鉴权和委派，重建并发控制仍集中在排行榜维护服务中。
 */
@Service
public class AdminRankingServiceImpl implements AdminRankingService {

    /** 实际执行总榜构建、原子切换和分布式锁保护的维护服务。 */
    private final HotRankingMaintenanceService hotRankingMaintenanceService;

    public AdminRankingServiceImpl(HotRankingMaintenanceService hotRankingMaintenanceService) {
        this.hotRankingMaintenanceService = hotRankingMaintenanceService;
    }

    /**
     * 仅允许 JWT 已认证的管理员触发。普通用户即使知道后台地址也不能覆盖线上总榜。
     */
    @Override
    public void rebuildAllHotRanking() {
        LoginUser loginUser = UserContextHolder.getRequired();
        if (!loginUser.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        hotRankingMaintenanceService.rebuildAllHotRanking();
    }
}
