package com.john.campus.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.util.StringUtils;

/**
 * Redisson 客户端配置：复用现有 Redis 单机连接参数，并为长时间的下载增量同步锁启用看门狗续期。
 */
@Configuration
public class RedissonConfig {

    /**
     * 创建由 Spring 管理的 Redisson 客户端。业务获取锁时不传 leaseTime，Redisson 才会使用此超时值启动看门狗自动续期。
     */
    @Bean(destroyMethod = "shutdown")
    @Lazy
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password,
            @Value("${spring.data.redis.database:0}") int database,
            @Value("${rank.sync.download-delta.lock-watchdog-timeout-ms:30000}") long lockWatchdogTimeoutMs) {
        if (port < 1 || database < 0 || lockWatchdogTimeoutMs < 1) {
            throw new IllegalArgumentException("Redisson 端口、数据库编号和看门狗超时必须合法");
        }

        Config config = new Config();
        // 看门狗会周期性延长未指定 leaseTime 的 RLock，避免长批次因固定 TTL 到期而被其他实例并发处理。
        config.setLockWatchdogTimeout(lockWatchdogTimeoutMs);
        // 测试或 Redis 临时不可用时不在 Spring 容器启动阶段强制建连，实际获取锁时再建立连接并由同步任务安全降级。
        config.setLazyInitialization(true);
        if (StringUtils.hasText(password)) {
            // Redisson 4.x 将认证信息提升到 Config，避免使用 SingleServerConfig 中已废弃的密码设置。
            config.setPassword(password);
        }

        SingleServerConfig singleServerConfig = config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database);
        return Redisson.create(config);
    }
}
