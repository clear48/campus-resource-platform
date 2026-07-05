package com.john.campus.service;

import com.john.campus.vo.UserVO;

/**
 * 用户业务接口，当前主要服务于当前用户查询。
 */
public interface UserService {

    /**
     * 根据登录上下文查询当前用户，并返回安全的用户 VO。
     */
    UserVO getCurrentUser();
}
