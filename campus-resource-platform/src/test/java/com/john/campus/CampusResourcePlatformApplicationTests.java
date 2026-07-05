package com.john.campus;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Spring Boot 上下文加载测试，用于尽早发现 Bean 配置和依赖注入问题。
 */
@SpringBootTest
class CampusResourcePlatformApplicationTests {

    /**
     * 只验证应用上下文能启动，不覆盖具体业务接口。
     */
    @Test
    void contextLoads() {
    }

}
