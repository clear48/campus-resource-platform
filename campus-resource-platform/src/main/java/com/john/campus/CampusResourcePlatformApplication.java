package com.john.campus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 后端服务启动入口，负责加载 Spring Boot 自动配置和项目内的组件扫描。
 */
@SpringBootApplication
@EnableScheduling
public class CampusResourcePlatformApplication {

    /**
     * 应用进程入口，本地开发和部署启动都会从这里引导 Spring 容器。
     */
    public static void main(String[] args) {
        SpringApplication.run(CampusResourcePlatformApplication.class, args);
    }

}
