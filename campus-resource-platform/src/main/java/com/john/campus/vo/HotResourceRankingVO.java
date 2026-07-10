package com.john.campus.vo;

import java.math.BigDecimal;

/**
 * 热门资料榜单项，只返回公开展示字段和统计快照，不暴露资料实体内部字段。
 *
 * @param rank 当前榜单中的连续名次，从 1 开始
 * @param resourceId 资料 ID
 * @param title 资料标题
 * @param courseName 课程名称
 * @param downloadCount MySQL 中的下载次数快照
 * @param favoriteCount MySQL 中的收藏次数快照
 * @param hotScore Redis 实时热度分或 MySQL 降级快照
 */
public record HotResourceRankingVO(
        Integer rank,
        Long resourceId,
        String title,
        String courseName,
        Long downloadCount,
        Long favoriteCount,
        BigDecimal hotScore) {
}
