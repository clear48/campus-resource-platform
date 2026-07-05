package com.john.campus.vo;

/**
 * 登录成功响应对象，包含客户端后续请求所需的 Token 信息和用户信息。
 *
 * @param accessToken JWT 字符串
 * @param tokenType Token 类型，当前固定为 Bearer
 * @param expiresIn Token 有效期，单位秒
 * @param user 当前登录用户信息
 */
public record AuthLoginVO(String accessToken, String tokenType, long expiresIn, UserVO user) {
}
