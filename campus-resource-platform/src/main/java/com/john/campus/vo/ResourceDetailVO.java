package com.john.campus.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 公开资料详情响应对象，只返回展示字段和统计快照，不暴露真实文件存储路径。
 *
 * @param resourceId 资料 ID
 * @param title 资料标题
 * @param description 资料简介
 * @param categoryId 分类 ID
 * @param categoryName 分类名称，后续查询时可由分类表补齐
 * @param courseName 课程名称
 * @param resourceType 资料类型
 * @param tags 标签列表，由 resource.tags 拆分得到
 * @param status 资料状态，公开详情只应返回审核通过状态
 * @param downloadCount 下载次数快照
 * @param favoriteCount 收藏次数快照
 * @param hotScore 热度分快照
 * @param createdAt 资料创建时间
 * @param favorited 当前用户是否已收藏；游客或收藏模块未接入时可为空
 */
public record ResourceDetailVO(
        Long resourceId,
        String title,
        String description,
        Long categoryId,
        String categoryName,
        String courseName,
        Integer resourceType,
        List<String> tags,
        Integer status,
        Long downloadCount,
        Long favoriteCount,
        BigDecimal hotScore,
        LocalDateTime createdAt,
        Boolean favorited) {
}
