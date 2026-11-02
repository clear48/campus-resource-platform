package com.john.campus.interceptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.john.campus.common.*;
import com.john.campus.exception.BusinessException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 请求上下文和新会话归属校验，不启动 Spring 或外部 Redis。 */
class JwtAuthenticationInterceptorTest {
    @Test
    void sessionMustBelongToSignedUserAndContextMustBeCleared() {
        JwtUtils jwt = mock(JwtUtils.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(jwt.parseToken("access")).thenReturn(new JwtClaims(7L, 1, "jti",
                LocalDateTime.now(), LocalDateTime.now().plusMinutes(15), "sid"));
        var interceptor = new JwtAuthenticationInterceptor(jwt, redis);
        var req = new MockHttpServletRequest(); req.addHeader("Authorization", "Bearer access");
        var response = new MockHttpServletResponse();
        when(hashes.get(RedisKeyConstants.authSession("sid"), "userId")).thenReturn("8");
        assertEquals(40105, assertThrows(BusinessException.class,
                () -> interceptor.preHandle(req, response, new Object())).getCode());
        when(hashes.get(RedisKeyConstants.authSession("sid"), "userId")).thenReturn("7");
        assertTrue(interceptor.preHandle(req, response, new Object()));
        assertEquals(7L, UserContextHolder.getRequiredUserId());
        interceptor.afterCompletion(req, response, new Object(), null);
        assertNull(UserContextHolder.get());
    }
}
