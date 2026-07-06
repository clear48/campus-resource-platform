package com.john.campus.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 待审核资料列表项，面向管理员展示审核决策所需的核心资料信息。
 *
 * @param resourceId 资料 ID
 * @param title 资料标题
 * @param description 资料简介，帮助管理员快速判断内容是否完整
 * @param categoryId 分类 ID，后续可用于关联分类名称
 * @param courseName 课程名称
 * @param resourceType 资料类型
 * @param tags 标签列表，由 resource.tags 拆分得到
 * @param fileId 关联文件 ID，后续审核详情或下载预览可使用
 * @param uploaderId 上传者 ID
 * @param status 当前资料状态，待审核列表中应固定为 PENDING_REVIEW
 * @param createdAt 资料创建时间
 */
public record PendingReviewResourceVO(
        Long resourceId,
        String title,
        String description,
        Long categoryId,
        String courseName,
        Integer resourceType,
        List<String> tags,
        Long fileId,
        Long uploaderId,
        Integer status,
        LocalDateTime createdAt) {
}
