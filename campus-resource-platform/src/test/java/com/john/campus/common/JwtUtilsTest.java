package com.john.campus.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

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
