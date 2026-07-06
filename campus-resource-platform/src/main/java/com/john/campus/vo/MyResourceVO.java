package com.john.campus.vo;

import java.time.LocalDateTime;

/**
 * 我的上传资料列表项，面向上传者展示资料审核状态和失败原因。
 *
 * @param resourceId 资料 ID
 * @param title 资料标题
 * @param courseName 课程名称
 * @param status 当前审核状态
 * @param rejectReason 最近一次拒绝原因
 * @param offlineReason 最近一次下架原因
 * @param createdAt 资料创建时间
 */
public record MyResourceVO(
        Long resourceId,
        String title,
        String courseName,
        Integer status,
        String rejectReason,
        String offlineReason,
        LocalDateTime createdAt) {
}
