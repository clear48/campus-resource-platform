package com.john.campus.vo;

/**
 * 当前登录用户对指定资料的收藏状态，不暴露收藏记录内部字段。
 *
 * @param resourceId 资料 ID
 * @param favorited  当前用户是否已收藏
 */
public record FavoriteStatusVO(Long resourceId, Boolean favorited) {
}
