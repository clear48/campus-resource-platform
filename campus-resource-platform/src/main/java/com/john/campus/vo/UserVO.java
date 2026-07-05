package com.john.campus.vo;

/**
 * 对外用户信息响应对象，刻意不包含 passwordHash 等敏感字段。
 *
 * @param userId 用户 ID
 * @param username 登录账号
 * @param nickname 昵称
 * @param email 邮箱
 * @param role 用户角色
 * @param status 用户状态
 */
public record UserVO(Long userId, String username, String nickname, String email, Integer role, Integer status) {
}
