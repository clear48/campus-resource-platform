package com.john.campus.vo;

import java.time.LocalDateTime;

/**
 * 应用就绪状态快照。
 *
 * @param application 应用名称
 * @param status 总体状态，所有关键组件均正常时为 UP
 * @param components 各关键组件状态
 * @param checkedAt 检查时间
 */
public record ReadinessVO(
        String application,
        String status,
        ReadinessComponentsVO components,
        LocalDateTime checkedAt) {

    /**
     * Controller 使用该判断映射 HTTP 状态，避免重复硬编码总体状态规则。
     */
    public boolean ready() {
        return "UP".equals(status);
    }
}
