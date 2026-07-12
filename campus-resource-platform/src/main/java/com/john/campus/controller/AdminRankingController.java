package com.john.campus.controller;

import com.john.campus.common.ApiResponse;
import com.john.campus.service.AdminRankingService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 排行榜后台运维入口：Controller 只提供 HTTP 路由和统一响应，权限与重建逻辑由 Service 处理。
 */
@RestController
@RequestMapping("/api/v1/admin/rankings")
public class AdminRankingController {

    private final AdminRankingService adminRankingService;

    public AdminRankingController(AdminRankingService adminRankingService) {
        this.adminRankingService = adminRankingService;
    }

    /** 管理员按 MySQL 当前统计值手动重建 Redis all 总榜。 */
    @PostMapping("/resources/hot/rebuild")
    public ApiResponse<Void> rebuildAllHotRanking() {
        adminRankingService.rebuildAllHotRanking();
        return ApiResponse.success();
    }
}
