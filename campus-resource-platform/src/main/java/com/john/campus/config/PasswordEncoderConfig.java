package com.john.campus.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码编码器配置，认证模块统一通过 PasswordEncoder 处理密码。
 */
@Configuration
public class PasswordEncoderConfig {

    /**
     * BCrypt 自带随机盐，适合保存用户密码哈希，避免明文或简单摘要存储。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
