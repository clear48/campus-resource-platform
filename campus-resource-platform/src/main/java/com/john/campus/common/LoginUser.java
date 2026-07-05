package com.john.campus.common;

/**
 * 当前请求内的登录用户快照，来自 JWT 解析结果。
 *
 * @param userId 用户 ID
 * @param role 用户角色
 * @param jti 当前 Token 的唯一标识
 */
public record LoginUser(Long userId, Integer role, String jti) {

    /**
     * 管理员判断集中在登录用户对象中，后续权限校验可直接复用。
     */
    public boolean isAdmin() {
        return Integer.valueOf(2).equals(role);
    }
}
