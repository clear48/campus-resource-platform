package com.john.campus.interceptor;

import com.john.campus.common.ErrorCode;
import com.john.campus.common.JwtClaims;
import com.john.campus.common.JwtUtils;
import com.john.campus.common.LoginUser;
import com.john.campus.common.RedisKeyConstants;
import com.john.campus.common.UserContextHolder;
import com.john.campus.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * JWT 鉴权拦截器，负责从请求头识别登录用户并写入 UserContextHolder。
 */
@Component
public class JwtAuthenticationInterceptor implements HandlerInterceptor {

    /**
     * 统一读取 Authorization 头，客户端必须传递 Bearer Token。
     */
    public static final String AUTHORIZATION_HEADER = "Authorization";
    public static final String BEARER_PREFIX = "Bearer ";
    /**
     * 原始 Token 写入 request attribute，退出登录接口需要复用它写黑名单。
     */
    public static final String ACCESS_TOKEN_ATTRIBUTE = "accessToken";
    public static final String CURRENT_USER_ID_ATTRIBUTE = "currentUserId";
    public static final String CURRENT_USER_ROLE_ATTRIBUTE = "currentUserRole";
    public static final String CURRENT_JTI_ATTRIBUTE = "currentJti";

    /**
     * JWT 工具负责签名校验和 Claims 解析。
     */
    private final JwtUtils jwtUtils;
    /**
     * Redis 用于查询 Token 黑名单，实现退出登录后的服务端失效能力。
     */
    private final StringRedisTemplate stringRedisTemplate;

    public JwtAuthenticationInterceptor(JwtUtils jwtUtils, StringRedisTemplate stringRedisTemplate) {
        this.jwtUtils = jwtUtils;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 请求进入 Controller 前完成登录校验；校验失败直接抛业务异常，由全局异常处理统一返回。
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            // 浏览器跨域预检请求不携带业务 Token，直接放行交给 CORS 配置处理。
            return true;
        }

        String authorization = request.getHeader(AUTHORIZATION_HEADER);
        if (!StringUtils.hasText(authorization) || !authorization.startsWith(BEARER_PREFIX)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        String token = authorization.substring(BEARER_PREFIX.length());
        JwtClaims claims = jwtUtils.parseToken(token);
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(RedisKeyConstants.tokenBlacklist(claims.jti())))) {
            // 黑名单命中说明用户已退出登录或 Token 被主动失效。
            throw new BusinessException(ErrorCode.TOKEN_BLACKLISTED);
        }

        LoginUser loginUser = new LoginUser(claims.userId(), claims.role(), claims.jti());

        // 同时写入 ThreadLocal 和 request attribute：Service 层用前者，Controller 特殊场景可用后者。
        UserContextHolder.set(loginUser);
        request.setAttribute(ACCESS_TOKEN_ATTRIBUTE, token);
        request.setAttribute(CURRENT_USER_ID_ATTRIBUTE, claims.userId());
        request.setAttribute(CURRENT_USER_ROLE_ATTRIBUTE, claims.role());
        request.setAttribute(CURRENT_JTI_ATTRIBUTE, claims.jti());
        return true;
    }

    /**
     * 请求结束后清理 ThreadLocal，避免容器线程复用时把上一个用户带到下一个请求。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContextHolder.clear();
    }
}
