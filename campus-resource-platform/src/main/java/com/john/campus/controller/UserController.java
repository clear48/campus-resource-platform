package com.john.campus.controller;

import com.john.campus.common.ApiResponse;
import com.john.campus.service.UserService;
import com.john.campus.vo.UserVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户接口入口，当前只提供“当前登录用户”查询能力。
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    /**
     * 用户业务服务，负责根据当前登录上下文查询用户。
     */
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * 查询当前登录用户，身份来源于 JWT 拦截器写入的 UserContextHolder。
     */
    @GetMapping("/me")
    public ApiResponse<UserVO> me() {
        return ApiResponse.success(userService.getCurrentUser());
    }
}
