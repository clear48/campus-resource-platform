package com.john.campus.service;

import com.john.campus.dto.HotResourceRankingQueryDTO;
import com.john.campus.dto.HotSearchKeywordRankingQueryDTO;
import com.john.campus.vo.HotResourceRankingVO;
import com.john.campus.vo.HotSearchKeywordRankingVO;
import java.util.List;

/**
 * 排行榜查询业务接口，负责向公开接口提供热门资料和热门搜索词数据。
 */
public interface RankingService {

    /**
     * 查询热门资料，优先使用 Redis 实时热度分，必要时降级为 MySQL 热度快照。
     */
    List<HotResourceRankingVO> listHotResources(HotResourceRankingQueryDTO query);

    /**
     * 查询热门搜索词；该运营数据 Redis 不可用时返回空列表，不影响核心业务。
     */
    List<HotSearchKeywordRankingVO> listHotSearchKeywords(HotSearchKeywordRankingQueryDTO query);

    /**
     * 记录一次已去重的有效下载；四个资料热度榜统一增加下载权重，Redis 故障不得影响下载主流程。
     */
    void recordResourceDownload(Long resourceId);

    /**
     * 记录一次真实发生的收藏状态变更；重复收藏由调用方识别后不得调用本方法。
     */
    void recordResourceFavorite(Long resourceId);

    /**
     * 记录一次真实发生的取消收藏状态变更；重复取消或取消失败不得调用本方法。
     */
    void recordResourceUnfavorite(Long resourceId);

    /**
     * 审核通过后为资料创建四个周期榜的成员；已有成员保留原有分数，避免重试把热度归零。
     */
    void initializeApprovedResource(Long resourceId);

    /**
     * 资料下架后从全部周期榜移除，查询侧仍会以 MySQL APPROVED 状态作二次过滤。
     */
    void removeOfflineResource(Long resourceId);
}
