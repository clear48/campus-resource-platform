package com.john.campus.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 详情缓存独立调度器配置测试，防止延迟双删误用现有批处理调度线程池。
 */
class ResourceDetailCacheConfigTest {

    @Test
    void resourceDetailSchedulerShouldHaveDedicatedBeanNameAndShutdownPolicy() throws Exception {
        Method factoryMethod = ResourceDetailCacheConfig.class.getMethod("resourceDetailCacheTaskScheduler");
        Bean bean = factoryMethod.getAnnotation(Bean.class);
        ThreadPoolTaskScheduler scheduler = new ResourceDetailCacheConfig().resourceDetailCacheTaskScheduler();

        assertThat(bean.name()).containsExactly("resourceDetailCacheTaskScheduler");
        assertThat(scheduler.getPoolSize()).isEqualTo(1);
        assertThat(scheduler.getThreadNamePrefix()).isEqualTo("resource-detail-cache-delete-");
        assertThat(ReflectionTestUtils.getField(scheduler, "waitForTasksToCompleteOnShutdown")).isEqualTo(true);
        assertThat(ReflectionTestUtils.getField(scheduler, "awaitTerminationMillis")).isEqualTo(5000L);
    }

    @Test
    void defaultSchedulerShouldKeepConventionalBeanName() throws Exception {
        Method factoryMethod = ResourceDetailCacheConfig.class.getMethod(
                "taskScheduler", org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder.class);

        assertThat(factoryMethod.getAnnotation(Bean.class).name()).containsExactly("taskScheduler");
    }
}
