package com.john.campus.config;

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 资料详情缓存配置，显式保留 Spring Boot 默认 TaskScheduler。
 */
@Configuration
public class ResourceDetailCacheConfig {

    /**
     * 显式声明默认调度器 Bean。Spring Boot 检测到自定义 TaskScheduler Bean 后自动配置会退让，
     * 因此这里继续用 Boot Builder 承接 spring.task.scheduling 配置。
     */
    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.build();
    }
}
