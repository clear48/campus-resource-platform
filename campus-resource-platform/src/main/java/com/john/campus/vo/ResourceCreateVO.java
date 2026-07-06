package com.john.campus.vo;

/**
 * 创建资料响应对象，告知前端资料已进入审核流程。
 *
 * @param resourceId 新创建的资料 ID
 * @param fileId 资料关联的文件 ID
 * @param status 创建后的资料状态，首版固定为待审核
 * @param statusName 状态英文名，便于前端直接展示或映射
 * @param message 创建结果提示
 */
public record ResourceCreateVO(
        Long resourceId,
        Long fileId,
        Integer status,
        String statusName,
        String message) {
}
