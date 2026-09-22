package com.john.campus.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.john.campus.common.ApiResponse;
import com.john.campus.service.HealthService;
import com.john.campus.vo.ReadinessComponentsVO;
import com.john.campus.vo.ReadinessVO;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 健康检查入口测试，验证就绪状态与 HTTP 状态码的映射。
 */
@ExtendWith(MockitoExtension.class)
class HealthControllerTest {

    @Mock
    HealthService healthService;

    @Test
    void downReadinessShouldReturnServiceUnavailable() {
        ReadinessVO readiness = new ReadinessVO(
                "campus-resource-platform",
                "DOWN",
                new ReadinessComponentsVO("UP", "DOWN", "UP"),
                LocalDateTime.now());
        when(healthService.checkReadiness()).thenReturn(readiness);
        HealthController controller = new HealthController(healthService);

        ResponseEntity<ApiResponse<ReadinessVO>> response = controller.readiness();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data()).isEqualTo(readiness);
    }

    @Test
    void upReadinessShouldReturnOk() {
        ReadinessVO readiness = new ReadinessVO(
                "campus-resource-platform",
                "UP",
                new ReadinessComponentsVO("UP", "UP", "UP"),
                LocalDateTime.now());
        when(healthService.checkReadiness()).thenReturn(readiness);
        HealthController controller = new HealthController(healthService);

        ResponseEntity<ApiResponse<ReadinessVO>> response = controller.readiness();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
