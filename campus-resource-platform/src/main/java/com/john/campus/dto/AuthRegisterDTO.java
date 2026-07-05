package com.john.campus.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 注册请求参数，负责承载用户注册所需的基础资料。
 */
@Getter
@Setter
public class AuthRegisterDTO {

    /**
     * 登录账号，数据库通过 uk_user_username 做最终唯一性兜底。
     */
    @NotBlank(message = "用户名不能为空")
    @Size(max = 50, message = "用户名长度不能超过 50")
    private String username;

    /**
     * 原始密码，Service 层会用 BCrypt 加密后保存 password_hash。
     */
    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 50, message = "密码长度必须在 8 到 50 位之间")
    private String password;

    /**
     * 昵称用于页面展示，和登录账号分离。
     */
    @NotBlank(message = "昵称不能为空")
    @Size(max = 50, message = "昵称长度不能超过 50")
    private String nickname;

    /**
     * 邮箱允许为空；非空时必须符合邮箱格式，数据库唯一索引负责防重复。
     */
    @Email(message = "邮箱格式不正确")
    @Size(max = 100, message = "邮箱长度不能超过 100")
    private String email;

    /**
     * 手机号允许为空；正则当前按中国大陆手机号格式做基础校验。
     */
    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;
}
