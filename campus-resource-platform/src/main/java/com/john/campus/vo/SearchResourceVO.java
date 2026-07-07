package com.john.campus.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 搜索资料列表项，只返回公开展示字段和统计快照，不暴露文件存储信息。
 *
 * @param resourceId 资料 ID
 * @param title 资料标题
 * @param description 资料简介
 * @param courseName 课程名称
 * @param resourceType 资料类型
 * @param tags 标签列表，由 resource.tags 拆分得到
 * @param downloadCount 下载次数快照
 * @param favoriteCount 收藏次数快照
 * @param hotScore 热度分快照
 * @param createdAt 资料创建时间
 */
public record SearchResourceVO(
        Long resourceId,
        String title,
        String description,
        String courseName,
        Integer resourceType,
        List<String> tags,
        Long downloadCount,
        Long favoriteCount,
        BigDecimal hotScore,
        LocalDateTime createdAt) {
}
