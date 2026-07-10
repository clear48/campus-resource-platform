package com.john.campus.vo;

import java.time.LocalDateTime;

/**
 * 我的收藏列表项，组合资料展示信息与用户的收藏时间。
 *
 * @param resourceId    资料 ID
 * @param title         资料标题
 * @param courseName    课程名称
 * @param downloadCount 资料下载次数快照
 * @param favoriteCount 资料收藏次数快照
 * @param createdAt     资料创建时间
 * @param favoriteAt    当前用户的收藏时间
 */
public record MyFavoriteVO(
        Long resourceId,
        String title,
        String courseName,
        Long downloadCount,
        Long favoriteCount,
        LocalDateTime createdAt,
        LocalDateTime favoriteAt) {
}
