package com.john.campus.config;

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 资料详情缓存调度配置，将延迟双删与现有批量定时任务隔离到不同线程池。
 */
@Configuration
public class ResourceDetailCacheConfig {

    /**
     * 显式保留 Spring Boot 原有的默认调度器。新增任意 TaskScheduler Bean 后自动配置会退让，
     * 因此这里继续用 Boot Builder 承接 spring.task.scheduling 配置，避免现有 @Scheduled 任务误用详情删除线程。
     */
    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.build();
    }

    /**
     * 延迟双删只需要单线程按时触发；关闭时等待已接收任务完成，减少发布窗口中的缓存失效遗漏。
     */
    @Bean(name = "resourceDetailCacheTaskScheduler")
    public ThreadPoolTaskScheduler resourceDetailCacheTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("resource-detail-cache-delete-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }
}
