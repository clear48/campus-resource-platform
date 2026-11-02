package com.john.campus.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.john.campus.common.ErrorCode;
import com.john.campus.common.RedisKeyConstants;
import com.john.campus.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/** 仅验证 Java 凭据处理及 Redis 返回值边界，不将 mock 结果当作 Lua 原子性证明。 */
class AuthSessionServiceTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
    private final AuthSessionService sessions = new AuthSessionService(redis);
    private AuthSessionService.Credential credential;

    @BeforeEach
    void prepareCredential() {
        credential = sessions.newCredential(null);
        when(redis.opsForHash()).thenReturn(hashes);
    }

    @Test
    void generatedCredentialsShouldRoundTripWithIndependentSecretsAndSha256Digest() throws Exception {
        var next = sessions.newCredential(credential.sid());
        assertThat(credential.sid()).matches("[0-9a-f]{32}");
        assertThat(credential.token()).matches("[0-9a-f]{32}\\.[A-Za-z0-9_-]{43}");
        assertThat(next.sid()).isEqualTo(credential.sid());
        assertThat(next.token()).isNotEqualTo(credential.token());
        assertThat(credential.hash()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(credential.token().getBytes(StandardCharsets.UTF_8))));
        assertThat(sessions.parse(credential.token())).isEqualTo(credential);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "Bearer token", "fake.jwt.token", "../session.secret"})
    void malformedCredentialsShouldBeRejected(String token) {
        assertInvalid(() -> sessions.parse(token));
    }

    @Test
    void inspectionShouldReturnUserAndRemainingAbsoluteLifetime() {
        scriptResult(1L);
        when(hashes.get(key(), "userId")).thenReturn("10001");
        when(redis.getExpire(key())).thenReturn(37L);
        assertThat(sessions.inspect(credential)).isEqualTo(new AuthSessionService.Session(10001L, 37L));
    }

    @Test
    void missingOrExpiredSessionShouldNeverBecomeAuthenticated() {
        scriptResult(0L);
        assertInvalid(() -> sessions.inspect(credential));
        scriptResult(1L);
        assertInvalid(() -> sessions.inspect(credential));
        when(hashes.get(key(), "userId")).thenReturn("10001");
        for (Long ttl : new Long[] {null, -2L, -1L, 0L}) {
            when(redis.getExpire(key())).thenReturn(ttl);
            assertInvalid(() -> sessions.inspect(credential));
        }
    }

    @Test
    void rotationShouldReturnRemainingLifetimeAndRejectFailedCas() {
        var next = sessions.newCredential(credential.sid());
        scriptResult(23L);
        assertThat(sessions.rotate(credential, next)).isEqualTo(23L);
        for (Long result : new Long[] {null, 0L, -1L}) {
            scriptResult(result);
            assertInvalid(() -> sessions.rotate(credential, next));
        }
    }

    @Test
    void revocationShouldBeIdempotentButRejectUnrecognizedSecret() {
        scriptResult(1L);
        sessions.revoke(credential);
        scriptResult(0L);
        sessions.revoke(credential);
        scriptResult(-1L);
        assertInvalid(() -> sessions.revoke(credential));
        scriptResult(null);
        assertThatThrownBy(() -> sessions.revoke(credential)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void creationShouldRejectUnconfirmedWriteAndRedisFailuresShouldRemainServerFailures() {
        scriptResult(0L);
        assertThatThrownBy(() -> sessions.create(credential, 10001L)).isInstanceOf(IllegalStateException.class);
        // 连接故障不得被伪装成凭据失效，否则前端会错误清空仍可恢复的登录态。
        doThrow(new RedisConnectionFailureException("test connection unavailable")).when(redis)
                .execute(any(RedisScript.class), anyList(), any(Object[].class));
        Stream.<Runnable>of(() -> sessions.create(credential, 10001L), () -> sessions.inspect(credential),
                () -> sessions.rotate(credential, sessions.newCredential(credential.sid())),
                () -> sessions.revoke(credential)).forEach(operation ->
                assertThatThrownBy(operation::run).isInstanceOf(RedisConnectionFailureException.class));
    }

    private String key() { return RedisKeyConstants.authSession(credential.sid()); }

    private void scriptResult(Long result) {
        doReturn(result).when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    private static void assertInvalid(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID.getCode()));
    }
}
