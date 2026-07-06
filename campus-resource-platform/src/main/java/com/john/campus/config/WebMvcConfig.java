package com.john.campus.config;

import com.john.campus.interceptor.JwtAuthenticationInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置，集中管理 JWT 拦截、跨域等 HTTP 基础能力。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * JWT 拦截器负责保护需要登录的 /api/v1/** 接口。
     */
    private final JwtAuthenticationInterceptor jwtAuthenticationInterceptor;

    public WebMvcConfig(JwtAuthenticationInterceptor jwtAuthenticationInterceptor) {
        this.jwtAuthenticationInterceptor = jwtAuthenticationInterceptor;
    }

    /**
     * 注册 JWT 拦截器，并显式排除公开接口，避免登录、注册和公开查询被误拦截。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtAuthenticationInterceptor)
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns(
                        "/api/v1/health",
                        "/api/v1/auth/login",
                        "/api/v1/auth/register",
                        // 分类是上传前公开枚举数据，只读查询不需要登录态。
                        "/api/v1/categories",
                        // 仅放行一段式资料详情路径，不能放行 /api/v1/resources，否则创建资料会绕过登录校验。
                        "/api/v1/resources/*",
                        "/api/v1/search/**",
                        "/api/v1/rankings/**",
                        "/error"
                );
    }

    /**
     * 本地开发阶段允许跨域调用，便于前端页面或接口工具访问后端接口。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
