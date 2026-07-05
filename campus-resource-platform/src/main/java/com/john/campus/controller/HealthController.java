package com.john.campus.controller;

import com.john.campus.common.ApiResponse;
import com.john.campus.service.HealthService;
import com.john.campus.vo.HealthVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查接口，供部署探活和联调时确认应用是否正常启动。
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    /**
     * 健康检查业务服务，Controller 只负责暴露 HTTP 入口。
     */
    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    /**
     * 返回应用健康状态；该接口已在 WebMvcConfig 中排除 JWT 拦截，无需登录即可访问。
     */
    @GetMapping
    public ApiResponse<HealthVO> health() {
        return ApiResponse.success(healthService.check());
    }
}
