package com.john.campus.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;

/**
 * 资料详情缓存配置测试，验证默认调度器 Bean 声明正确。
 */
class ResourceDetailCacheConfigTest {

    @Test
    void defaultSchedulerShouldKeepConventionalBeanName() throws Exception {
        Method factoryMethod = ResourceDetailCacheConfig.class.getMethod(
                "taskScheduler", org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder.class);

        assertThat(factoryMethod.getAnnotation(Bean.class).name()).containsExactly("taskScheduler");
    }
}
