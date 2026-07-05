package com.john.campus.service.impl;

import com.john.campus.service.PasswordService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 密码服务实现，统一使用 Spring Security 的 PasswordEncoder。
 */
@Service
public class PasswordServiceImpl implements PasswordService {

    /**
     * 当前 Bean 实际为 BCryptPasswordEncoder，由配置类注入。
     */
    private final PasswordEncoder passwordEncoder;

    public PasswordServiceImpl(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 注册时加密原始密码，数据库只保存哈希值。
     */
    @Override
    public String encode(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }

    /**
     * 登录校验时同时防御空密码和空哈希，避免 PasswordEncoder 抛出低层异常。
     */
    @Override
    public boolean matches(String rawPassword, String encodedPassword) {
        if (!StringUtils.hasText(rawPassword) || !StringUtils.hasText(encodedPassword)) {
            return false;
        }
        return passwordEncoder.matches(rawPassword, encodedPassword);
    }
}
