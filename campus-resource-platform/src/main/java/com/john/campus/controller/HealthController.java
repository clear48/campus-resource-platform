package com.john.campus.controller;

import com.john.campus.common.ApiResponse;
import com.john.campus.service.HealthService;
import com.john.campus.vo.HealthVO;
import com.john.campus.vo.ReadinessVO;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    /**
     * 返回接流量就绪状态；任一关键依赖不可用时返回 503，供容器和部署流程判定实例状态。
     */
    @GetMapping("/readiness")
    public ResponseEntity<ApiResponse<ReadinessVO>> readiness() {
        ReadinessVO readiness = healthService.checkReadiness();
        HttpStatus status = readiness.ready() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(ApiResponse.success(readiness));
    }
}
