package com.john.campus.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 登录请求参数，Controller 使用 Validation 注解完成基础非空校验。
 */
@Getter
@Setter
public class AuthLoginDTO {

    /**
     * 登录账号，目前按 username 查询 user 表。
     */
    @NotBlank(message = "用户名不能为空")
    private String username;

    /**
     * 原始密码只用于本次校验，不会被写入响应或日志。
     */
    @NotBlank(message = "密码不能为空")
    private String password;
}
