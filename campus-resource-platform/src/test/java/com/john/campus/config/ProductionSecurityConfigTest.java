package com.john.campus.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 生产安全配置测试，覆盖账号、密码隔离与磁盘水位的启动拒绝规则。
 */
class ProductionSecurityConfigTest {

    private static final String MYSQL_PASSWORD = "mysql-correct-horse-battery-staple";
    private static final String REDIS_PASSWORD = "redis-purple-ocean-window-2026";
    private static final String JWT_SECRET = "jwt-forest-cloud-compass-signer-2026";

    @Test
    void validSameOriginProductionConfigurationShouldPass() {
        ProductionSecurityConfig config = config(
                "campus_app", MYSQL_PASSWORD, REDIS_PASSWORD, JWT_SECRET, 1L);

        assertThatCode(config::validate).doesNotThrowAnyException();
    }

    @Test
    void rootApplicationAccountShouldBeRejectedWithoutLeakingPassword() {
        ProductionSecurityConfig config = config("root", MYSQL_PASSWORD, REDIS_PASSWORD, JWT_SECRET, 1L);

        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("root")
                .hasMessageNotContaining(MYSQL_PASSWORD);
    }

    @Test
    void weakMysqlOrRedisPasswordShouldBeRejected() {
        assertThatThrownBy(() -> config("campus_app", "password", REDIS_PASSWORD, JWT_SECRET, 1L).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MySQL application password");
        assertThatThrownBy(() -> config("campus_app", MYSQL_PASSWORD, "redis", JWT_SECRET, 1L).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Redis password");
        assertThatThrownBy(() -> config(
                "campus_app", "aaaaaaaaaaaaaaaa", REDIS_PASSWORD, JWT_SECRET, 1L).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MySQL application password");
    }

    @Test
    void reusedApplicationSecretsShouldBeRejected() {
        assertThatThrownBy(() -> config(
                "campus_app", MYSQL_PASSWORD, MYSQL_PASSWORD, JWT_SECRET, 1L).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different values")
                .hasMessageNotContaining(MYSQL_PASSWORD);
    }

    @Test
    void nonPositiveUploadReserveShouldBeRejected() {
        ProductionSecurityConfig config = config(
                "campus_app", MYSQL_PASSWORD, REDIS_PASSWORD, JWT_SECRET, 0L);

        assertThatThrownBy(config::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("minimum free space");
    }

    private ProductionSecurityConfig config(
            String mysqlUsername,
            String mysqlPassword,
            String redisPassword,
            String jwtSecret,
            long minFreeSpaceBytes) {
        return new ProductionSecurityConfig(
                mysqlUsername, mysqlPassword, redisPassword, jwtSecret, minFreeSpaceBytes);
    }
}
