package com.john.campus.entity;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 用户实体，对应 user 表，承载登录认证和角色状态信息。
 */
@Getter
@Setter
public class User extends BaseEntity {

    /**
     * 普通学生角色，后续上传、下载、收藏等能力默认面向该角色开放。
     */
    public static final int ROLE_STUDENT = 1;
    /**
     * 管理员角色，后续审核和后台管理接口会使用。
     */
    public static final int ROLE_ADMIN = 2;

    /**
     * 禁用用户不能登录，也不能继续访问当前用户信息。
     */
    public static final int STATUS_DISABLED = 0;
    /**
     * 正常用户可以登录和访问受保护接口。
     */
    public static final int STATUS_NORMAL = 1;

    /**
     * 登录账号，注册和登录都以 username 为主查询条件。
     */
    private String username;
    /**
     * BCrypt 加密后的密码哈希，禁止保存明文密码。
     */
    private String passwordHash;
    private String nickname;
    private String email;
    private String phone;
    /**
     * 用户角色，当前支持学生和管理员。
     */
    private Integer role;
    /**
     * 用户状态，认证流程必须校验是否正常。
     */
    private Integer status;
    private String avatarUrl;
    /**
     * 最近登录时间，登录成功后更新，便于后续运营或安全审计。
     */
    private LocalDateTime lastLoginAt;

    /**
     * 集中封装管理员判断，避免业务层散落角色魔法值。
     */
    public boolean isAdmin() {
        return Integer.valueOf(ROLE_ADMIN).equals(role);
    }

    /**
     * 集中封装账号可用性判断，登录和当前用户查询都会复用。
     */
    public boolean isNormal() {
        return Integer.valueOf(STATUS_NORMAL).equals(status);
    }
}
