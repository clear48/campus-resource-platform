package com.john.campus.common;

import java.time.LocalDateTime;

/**
 * JWT 解析后的可信载荷，拦截器和业务上下文只依赖这个对象，不直接传递第三方 Claims。
 *
 * @param userId 当前登录用户 ID
 * @param role 当前登录用户角色
 * @param jti Token 唯一标识，用于退出登录黑名单
 * @param issuedAt Token 签发时间
 * @param expiresAt Token 过期时间
 */
public record JwtClaims(Long userId, Integer role, String jti, LocalDateTime issuedAt, LocalDateTime expiresAt) {
}
