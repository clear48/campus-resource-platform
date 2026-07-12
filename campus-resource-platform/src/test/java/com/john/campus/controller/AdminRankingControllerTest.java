package com.john.campus.controller;

import static com.john.campus.interceptor.JwtAuthenticationInterceptor.AUTHORIZATION_HEADER;
import static com.john.campus.interceptor.JwtAuthenticationInterceptor.BEARER_PREFIX;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.john.campus.common.ErrorCode;
import com.john.campus.common.JwtClaims;
import com.john.campus.common.JwtUtils;
import com.john.campus.config.WebMvcConfig;
import com.john.campus.exception.BusinessException;
import com.john.campus.exception.GlobalExceptionHandler;
import com.john.campus.interceptor.JwtAuthenticationInterceptor;
import com.john.campus.service.AdminRankingService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** 管理员重建接口测试：覆盖 JWT 拦截、管理员拒绝和成功路由。 */
@WebMvcTest(AdminRankingController.class)
@Import({WebMvcConfig.class, JwtAuthenticationInterceptor.class, GlobalExceptionHandler.class})
class AdminRankingControllerTest {

    private static final String ADMIN_TOKEN = "admin-ranking-controller-token";
    private static final String STUDENT_TOKEN = "student-ranking-controller-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminRankingService adminRankingService;

    @MockitoBean
    private JwtUtils jwtUtils;

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void setUpTokens() {
        when(jwtUtils.parseToken(ADMIN_TOKEN)).thenReturn(claims(90001L, 2, "admin-jti"));
        when(jwtUtils.parseToken(STUDENT_TOKEN)).thenReturn(claims(10001L, 1, "student-jti"));
        when(stringRedisTemplate.hasKey(org.mockito.ArgumentMatchers.anyString())).thenReturn(false);
    }

    @Test
    void rebuildShouldRequireAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/admin/rankings/resources/hot/rebuild"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()));

        verify(adminRankingService, never()).rebuildAllHotRanking();
    }

    @Test
    void rebuildShouldRejectNonAdmin() throws Exception {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .when(adminRankingService)
                .rebuildAllHotRanking();

        mockMvc.perform(post("/api/v1/admin/rankings/resources/hot/rebuild").with(bearerToken(STUDENT_TOKEN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.getCode()));
    }

    @Test
    void adminShouldRebuildAllHotRanking() throws Exception {
        mockMvc.perform(post("/api/v1/admin/rankings/resources/hot/rebuild").with(bearerToken(ADMIN_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(adminRankingService).rebuildAllHotRanking();
    }

    private JwtClaims claims(Long userId, Integer role, String jti) {
        return new JwtClaims(userId, role, jti, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1));
    }

    private RequestPostProcessor bearerToken(String token) {
        return request -> {
            request.addHeader(AUTHORIZATION_HEADER, BEARER_PREFIX + token);
            return request;
        };
    }
}
