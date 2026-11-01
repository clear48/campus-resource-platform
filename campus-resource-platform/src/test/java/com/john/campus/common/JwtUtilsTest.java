package com.john.campus.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import com.john.campus.exception.BusinessException;

/**
 * JWT 密钥启动校验与基本签发解析测试，防止生产配置重新引入可用的公开默认密钥。
 */
class JwtUtilsTest {

    private static final long EXPIRATION_SECONDS = 3600;
    private static final String SAFE_TEST_SECRET =
            "campus-resource-platform-jwt-test-secret-20260715";

    @Test
    void constructorShouldRejectMissingBlankShortAndLegacySecrets() {
        assertThrows(IllegalArgumentException.class, () -> new JwtUtils(null, EXPIRATION_SECONDS));
        assertThrows(IllegalArgumentException.class, () -> new JwtUtils("", EXPIRATION_SECONDS));
        assertThrows(IllegalArgumentException.class, () -> new JwtUtils("   ", EXPIRATION_SECONDS));
        assertThrows(IllegalArgumentException.class, () -> new JwtUtils("too-short", EXPIRATION_SECONDS));
        assertThrows(IllegalArgumentException.class, () -> new JwtUtils(
                "campus-resource-platform-dev-secret-change-me",
                EXPIRATION_SECONDS));
    }

    @Test
    void expiredTokenShouldHaveDedicatedCodeButInvalidSignatureShouldNot() {
        JwtUtils expired = new JwtUtils(SAFE_TEST_SECRET, -5);
        String token = expired.generateToken(1L, 1);
        assertEquals(40103, assertThrows(BusinessException.class, () -> expired.parseToken(token)).getCode());
        JwtUtils otherKey = new JwtUtils(SAFE_TEST_SECRET + "-other", 3600);
        assertEquals(40101, assertThrows(BusinessException.class, () -> otherKey.parseToken(token)).getCode());
    }

    @Test
    void sessionTokenShouldCarrySidAndRespectRemainingSessionLifetime() {
        JwtUtils jwt = new JwtUtils(SAFE_TEST_SECRET, 900);
        JwtClaims claims = jwt.parseToken(jwt.generateToken(1L, 2, "test-session", 30));
        assertEquals("test-session", claims.sid());
        assertEquals(30, java.time.Duration.between(claims.issuedAt(), claims.expiresAt()).getSeconds());
    }

    @Test
    void safeSecretShouldGenerateAndParseToken() {
        JwtUtils jwtUtils = new JwtUtils(SAFE_TEST_SECRET, EXPIRATION_SECONDS);

        String token = jwtUtils.generateToken(10001L, 2);
        JwtClaims claims = jwtUtils.parseToken(token);

        assertEquals(10001L, claims.userId());
        assertEquals(2, claims.role());
        assertNotNull(claims.issuedAt());
        assertNotNull(claims.expiresAt());
        assertFalse(claims.jti().isBlank());
        assertEquals(EXPIRATION_SECONDS, jwtUtils.getExpirationSeconds());
    }
}
