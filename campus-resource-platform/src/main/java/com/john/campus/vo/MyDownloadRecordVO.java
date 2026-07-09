package com.john.campus.vo;

import java.time.LocalDateTime;

/**
 * 我的下载记录列表项，只返回前端展示所需字段，不暴露 IP、UA 等审计信息。
 *
 * @param downloadRecordId 下载记录 ID
 * @param resourceId       被下载资料 ID
 * @param title            资料标题，来自 resource 表关联查询
 * @param fileId           被下载文件 ID
 * @param downloadStatus   下载状态：1 成功 2 失败
 * @param createdAt        下载时间
 */
public record MyDownloadRecordVO(
        Long downloadRecordId,
        Long resourceId,
        String title,
        Long fileId,
        Integer downloadStatus,
        LocalDateTime createdAt) {
}
