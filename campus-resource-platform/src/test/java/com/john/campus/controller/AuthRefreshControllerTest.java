package com.john.campus.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.john.campus.common.JwtUtils;
import com.john.campus.config.WebMvcConfig;
import com.john.campus.exception.GlobalExceptionHandler;
import com.john.campus.interceptor.JwtAuthenticationInterceptor;
import com.john.campus.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Web 切片验证刷新/退出独立认证，不连接外部服务。 */
@WebMvcTest(AuthController.class)
@Import({WebMvcConfig.class, JwtAuthenticationInterceptor.class, GlobalExceptionHandler.class})
class AuthRefreshControllerTest {
    @Autowired private MockMvc mvc;
    @MockBean private AuthService auth;
    @MockBean private JwtUtils jwt;
    @MockBean private StringRedisTemplate redis;

    @Test
    void refreshShouldNotRequireValidAccess() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh").header("Authorization", "Bearer expired")
                .contentType("application/json").content("{\"refreshToken\":\"opaque\"}"))
                .andExpect(status().isOk());
        verify(auth).refresh("opaque");
        verifyNoInteractions(jwt);
    }

    @Test
    void refreshShouldRejectBlankBodyCredential() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh").contentType("application/json")
                .content("{\"refreshToken\":\" \"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(auth);
    }

    @Test
    void logoutShouldPassRefreshInsteadOfExpiredAccess() throws Exception {
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer expired")
                .contentType("application/json").content("{\"refreshToken\":\"opaque\"}"))
                .andExpect(status().isOk());
        verify(auth).logoutSession("opaque");
        verifyNoInteractions(jwt);
    }

    @Test
    void legacyLogoutShouldDelegateBearerValidationToService() throws Exception {
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer legacy"))
                .andExpect(status().isOk());
        verify(auth).logout("Bearer legacy");
    }
}
