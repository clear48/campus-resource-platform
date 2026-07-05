package com.john.campus.common;

import com.john.campus.exception.BusinessException;

/**
 * 当前请求用户上下文，使用 ThreadLocal 让 Service 层无需重复解析 Token。
 */
public final class UserContextHolder {

    /**
     * Web 容器线程会复用，因此请求结束后必须 clear，避免用户信息串线。
     */
    private static final ThreadLocal<LoginUser> USER_CONTEXT = new ThreadLocal<>();

    private UserContextHolder() {
    }

    /**
     * 拦截器鉴权成功后写入当前登录用户。
     */
    public static void set(LoginUser loginUser) {
        USER_CONTEXT.set(loginUser);
    }

    /**
     * 获取当前登录用户；允许返回 null，适合可选登录场景。
     */
    public static LoginUser get() {
        return USER_CONTEXT.get();
    }

    /**
     * 获取必须存在的登录用户，受保护接口缺失上下文时统一按未登录处理。
     */
    public static LoginUser getRequired() {
        LoginUser loginUser = get();
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return loginUser;
    }

    /**
     * 业务层最常用的身份读取方法，避免到处拆 LoginUser。
     */
    public static Long getRequiredUserId() {
        return getRequired().userId();
    }

    /**
     * 后续权限校验可直接读取当前角色。
     */
    public static Integer getRequiredRole() {
        return getRequired().role();
    }

    /**
     * 请求完成后清理线程变量，这是 ThreadLocal 使用的安全边界。
     */
    public static void clear() {
        USER_CONTEXT.remove();
    }
}
