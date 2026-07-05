package com.john.campus.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis 配置，集中声明 Mapper 接口扫描路径。
 */
@Configuration
@MapperScan("com.john.campus.mapper")
public class MyBatisConfig {
}
