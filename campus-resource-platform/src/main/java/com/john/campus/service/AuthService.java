package com.john.campus.service;

import com.john.campus.dto.AuthLoginDTO;
import com.john.campus.dto.AuthRegisterDTO;
import com.john.campus.vo.AuthLoginVO;
import com.john.campus.vo.UserVO;

/**
 * 认证业务接口，封装注册、登录和退出登录的核心流程。
 */
public interface AuthService {

    /**
     * 注册学生账号，成功后返回不含密码的用户信息。
     */
    UserVO register(AuthRegisterDTO request);

    /**
     * 登录并签发 JWT，失败时统一抛出业务异常。
     */
    AuthLoginVO login(AuthLoginDTO request);

    /**
     * 退出登录，将当前 Token 写入 Redis 黑名单。
     */
    void logout(String token);

    /** 校验并轮换刷新凭据，返回最新角色签发的双 Token。 */
    AuthLoginVO refresh(String refreshToken);

    /** 使用刷新凭据撤销整个会话，不依赖 Access Token 的有效期。 */
    void logoutSession(String refreshToken);
}
