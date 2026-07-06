package com.john.campus.vo;

import java.time.LocalDateTime;

/**
 * 审核记录响应对象，用于管理员查看某份资料的历史审核轨迹。
 *
 * @param auditRecordId 审核记录 ID
 * @param resourceId 被审核资料 ID
 * @param auditorId 执行操作的管理员 ID
 * @param actionType 审核动作类型：1通过 2拒绝 3下架
 * @param beforeStatus 操作前资料状态
 * @param afterStatus 操作后资料状态
 * @param auditReason 审核意见、拒绝原因或下架原因
 * @param createdAt 审核记录创建时间
 */
public record AuditRecordVO(
        Long auditRecordId,
        Long resourceId,
        Long auditorId,
        Integer actionType,
        Integer beforeStatus,
        Integer afterStatus,
        String auditReason,
        LocalDateTime createdAt) {
}
