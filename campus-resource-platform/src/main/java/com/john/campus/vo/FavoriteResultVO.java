package com.john.campus.vo;

/**
 * 收藏或取消收藏操作结果，只返回当前资料的收藏状态和统计快照。
 *
 * @param resourceId       资料 ID
 * @param favorited        操作后的收藏状态
 * @param duplicateIgnored 是否因重复收藏被幂等忽略
 * @param favoriteCount    资料当前收藏总数
 * @param hotScoreDelta    本次操作对热度分的变化，首版预留为 0
 */
public record FavoriteResultVO(
        Long resourceId,
        Boolean favorited,
        Boolean duplicateIgnored,
        Long favoriteCount,
        Integer hotScoreDelta) {
}
