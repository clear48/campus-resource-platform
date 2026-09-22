package com.john.campus.vo;

/**
 * 就绪检查的固定组件状态，只公开 UP/DOWN，不公开异常、连接信息或文件路径。
 *
 * @param mysql MySQL 状态
 * @param redis Redis 状态
 * @param storage 上传存储状态
 */
public record ReadinessComponentsVO(String mysql, String redis, String storage) {
}
