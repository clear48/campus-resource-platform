package com.john.campus.controller;

import com.john.campus.common.ApiResponse;
import com.john.campus.dto.AuthLoginDTO;
import com.john.campus.dto.AuthRegisterDTO;
import com.john.campus.interceptor.JwtAuthenticationInterceptor;
import com.john.campus.service.AuthService;
import com.john.campus.vo.AuthLoginVO;
import com.john.campus.vo.UserVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口入口，负责接收认证请求并把具体业务委托给 AuthService。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    /**
     * 认证业务服务，Controller 不直接访问 Mapper，保持请求层和业务层分离。
     */
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 用户注册入口，参数校验由 Validation 注解先执行，业务层负责去重和入库。
     */
    @PostMapping("/register")
    public ApiResponse<UserVO> register(@Valid @RequestBody AuthRegisterDTO request) {
        return ApiResponse.success(authService.register(request));
    }

    /**
     * 用户登录入口，成功后返回 Access Token 和当前用户基础信息。
     */
    @PostMapping("/login")
    public ApiResponse<AuthLoginVO> login(@Valid @RequestBody AuthLoginDTO request) {
        return ApiResponse.success(authService.login(request));
    }

    /**
     * 退出登录入口，依赖拦截器提前解析 Token 并写入 request attribute。
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        String token = (String) request.getAttribute(JwtAuthenticationInterceptor.ACCESS_TOKEN_ATTRIBUTE);
        authService.logout(token);
        return ApiResponse.success();
    }
}
