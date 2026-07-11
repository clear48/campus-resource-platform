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
}
