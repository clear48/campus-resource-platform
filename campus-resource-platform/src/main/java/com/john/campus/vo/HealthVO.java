package com.john.campus.vo;

import java.time.LocalDateTime;

/**
 * 健康检查响应对象，描述应用当前可用状态。
 *
 * @param application 应用名称
 * @param status 健康状态
 * @param checkedAt 检查时间
 */
public record HealthVO(String application, String status, LocalDateTime checkedAt) {
}
