package com.john.campus.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.john.campus.common.JwtUtils;
import com.john.campus.dto.AuthLoginDTO;
import com.john.campus.entity.User;
import com.john.campus.exception.BusinessException;
import com.john.campus.mapper.UserMapper;
import com.john.campus.service.AuthSessionService;
import com.john.campus.service.PasswordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 只使用 mock 验证双凭据业务编排，不连接数据库或 Redis。 */
class AuthServiceRefreshTest {
    private final UserMapper users = mock(UserMapper.class);
    private final PasswordService passwords = mock(PasswordService.class);
    private final JwtUtils jwt = new JwtUtils("unit-test-only-safe-secret-1234567890", 900);
    private final AuthSessionService sessions = mock(AuthSessionService.class);
    private final AuthServiceImpl service = new AuthServiceImpl(users, passwords, jwt,
            mock(StringRedisTemplate.class), sessions);
    private final AuthSessionService.Credential old = new AuthSessionService.Credential("sid", "old", "hash1");
    private final AuthSessionService.Credential next = new AuthSessionService.Credential("sid", "next", "hash2");
    private User user;

    @BeforeEach
    void setup() {
        user = new User();
        user.setId(7L); user.setUsername("student"); user.setRole(2); user.setStatus(1);
    }

    private void prepareRefresh() {
        when(sessions.parse("old")).thenReturn(old);
        when(sessions.inspect(old)).thenReturn(new AuthSessionService.Session(7L, 100));
        when(users.selectById(7L)).thenReturn(user);
        when(sessions.newCredential("sid")).thenReturn(next);
        when(sessions.rotate(old, next)).thenReturn(99L);
    }

    @Test
    void refreshShouldUseCurrentRoleAndRotateOnce() {
        prepareRefresh();
        var result = service.refresh("old");
        assertEquals("next", result.refreshToken());
        assertEquals(2, jwt.parseToken(result.accessToken()).role());
        assertEquals("sid", jwt.parseToken(result.accessToken()).sid());
        assertTrue(result.expiresIn() <= 100);
        assertEquals(99, result.refreshExpiresIn());
        verify(sessions).rotate(old, next);
    }

    @Test
    void disabledUserShouldRevokeWithoutRotation() {
        prepareRefresh(); user.setStatus(0);
        assertEquals(40104, assertThrows(BusinessException.class, () -> service.refresh("old")).getCode());
        verify(sessions).revoke(old);
        verify(sessions, never()).rotate(any(), any());
    }

    @Test
    void databaseFailureMustNotConsumeRefreshCredential() {
        prepareRefresh();
        when(users.selectById(7L)).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class, () -> service.refresh("old"));
        verify(sessions, never()).rotate(any(), any());
    }

    @Test
    void loginShouldCreateSessionBeforeReturningCredentials() {
        AuthLoginDTO dto = new AuthLoginDTO(); dto.setUsername("student"); dto.setPassword("password");
        user.setPasswordHash("hash");
        when(users.selectByUsername("student")).thenReturn(user);
        when(passwords.matches("password", "hash")).thenReturn(true);
        when(sessions.newCredential(null)).thenReturn(next);
        var result = service.login(dto);
        verify(sessions).create(next, 7L);
        assertEquals("next", result.refreshToken());
        assertEquals("sid", jwt.parseToken(result.accessToken()).sid());
    }

    @Test
    void logoutWithRefreshShouldRevokeIndependentOfAccessExpiry() {
        when(sessions.parse("old")).thenReturn(old);
        service.logoutSession("old");
        verify(sessions).revoke(old);
        assertEquals(40101, assertThrows(BusinessException.class, () -> service.logout(null)).getCode());
    }
}
