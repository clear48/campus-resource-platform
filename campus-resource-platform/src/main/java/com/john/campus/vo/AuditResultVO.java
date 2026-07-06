package com.john.campus.vo;

import java.time.LocalDateTime;

/**
 * 审核动作结果响应，统一承载通过、拒绝和下架后的状态流转结果。
 *
 * @param resourceId 被审核资料 ID
 * @param actionType 审核动作类型：1通过 2拒绝 3下架
 * @param beforeStatus 操作前资料状态
 * @param afterStatus 操作后资料状态
 * @param auditRecordId 本次写入的审核记录 ID
 * @param auditReason 审核意见、拒绝原因或下架原因
 * @param approvedAt 审核通过时间，仅通过动作有值
 * @param offlineAt 下架时间，仅下架动作有值
 */
public record AuditResultVO(
        Long resourceId,
        Integer actionType,
        Integer beforeStatus,
        Integer afterStatus,
        Long auditRecordId,
        String auditReason,
        LocalDateTime approvedAt,
        LocalDateTime offlineAt) {
}
