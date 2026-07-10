package com.john.campus.vo;

/**
 * 热门搜索词榜单项，只返回归一化关键词和搜索次数，不暴露用户或搜索明细。
 *
 * @param rank 当前榜单中的连续名次，从 1 开始
 * @param keyword 已归一化的搜索关键词
 * @param searchCount Redis ZSet 分数转换后的搜索次数
 */
public record HotSearchKeywordRankingVO(
        Integer rank,
        String keyword,
        Long searchCount) {
}
