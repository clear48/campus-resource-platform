package com.john.campus.config;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;

/**
 * 生产环境安全配置闸门，在应用接收流量前拒绝明显不安全或缺失的关键配置。
 * 校验异常只描述配置项，不拼接实际值，避免 Secret 进入启动日志。
 */
@Configuration
@Profile("prod")
public class ProductionSecurityConfig {

    private static final int MIN_PASSWORD_BYTES = 16;
    private static final Set<String> COMMON_WEAK_PASSWORDS = Set.of(
            "password", "password123", "123456", "12345678", "admin", "redis", "mysql", "change-me");

    private final String mysqlUsername;
    private final String mysqlPassword;
    private final String redisPassword;
    private final String jwtSecret;
    private final long uploadMinFreeSpaceBytes;

    public ProductionSecurityConfig(
            @Value("${spring.datasource.username:}") String mysqlUsername,
            @Value("${spring.datasource.password:}") String mysqlPassword,
            @Value("${spring.data.redis.password:}") String redisPassword,
            @Value("${app.jwt.secret:}") String jwtSecret,
            @Value("${app.upload.min-free-space-bytes:0}") long uploadMinFreeSpaceBytes) {
        this.mysqlUsername = mysqlUsername;
        this.mysqlPassword = mysqlPassword;
        this.redisPassword = redisPassword;
        this.jwtSecret = jwtSecret;
        this.uploadMinFreeSpaceBytes = uploadMinFreeSpaceBytes;
    }

    /**
     * 统一执行生产配置校验。JWT 密钥继续由 JwtUtils 的现有启动校验负责，避免重复维护规则。
     */
    @PostConstruct
    void validate() {
        validateMysqlAccount();
        validateStrongPassword("MySQL application password", mysqlPassword);
        validateStrongPassword("Redis password", redisPassword);
        validateSecretsAreDistinct();
        if (uploadMinFreeSpaceBytes <= 0) {
            throw new IllegalStateException("Upload minimum free space must be greater than zero in production");
        }
    }

    private void validateMysqlAccount() {
        if (!StringUtils.hasText(mysqlUsername)) {
            throw new IllegalStateException("MySQL application username must be configured in production");
        }
        if ("root".equalsIgnoreCase(mysqlUsername.trim())) {
            throw new IllegalStateException("MySQL root account must not be used by the application");
        }
    }

    private void validateStrongPassword(String configurationName, String password) {
        if (!StringUtils.hasText(password)
                || password.getBytes(StandardCharsets.UTF_8).length < MIN_PASSWORD_BYTES
                || isObviouslyWeak(password)) {
            // 不回显密码及其长度，防止启动失败信息泄露 Secret 特征。
            throw new IllegalStateException(configurationName + " is missing or too weak for production");
        }
    }

    private boolean isObviouslyWeak(String password) {
        String normalized = password.trim().toLowerCase(Locale.ROOT);
        long distinctCharacters = normalized.chars().distinct().limit(6).count();
        return COMMON_WEAK_PASSWORDS.contains(normalized)
                || normalized.contains("password")
                || normalized.contains("change-me")
                || distinctCharacters < 6;
    }

    private void validateSecretsAreDistinct() {
        // 生产 Secret 必须按用途隔离；任一凭据泄露时，不能连带取得 Redis、数据库和 JWT 签名能力。
        if (mysqlPassword.equals(redisPassword)
                || mysqlPassword.equals(jwtSecret)
                || redisPassword.equals(jwtSecret)) {
            throw new IllegalStateException("MySQL, Redis and JWT secrets must use different values in production");
        }
    }
}
