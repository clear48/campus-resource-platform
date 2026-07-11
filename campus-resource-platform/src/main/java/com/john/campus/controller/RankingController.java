package com.john.campus.controller;

import com.john.campus.common.ApiResponse;
import com.john.campus.dto.HotResourceRankingQueryDTO;
import com.john.campus.dto.HotSearchKeywordRankingQueryDTO;
import com.john.campus.service.RankingService;
import com.john.campus.vo.HotResourceRankingVO;
import com.john.campus.vo.HotSearchKeywordRankingVO;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 排行榜公开查询接口入口；只负责绑定并校验 HTTP 参数，再将排序、缓存降级等业务规则交给 Service。
 */
@RestController
@RequestMapping("/api/v1/rankings")
public class RankingController {

    /**
     * 排行榜查询服务；Controller 不直接操作 Redis 或 MySQL，避免传输层承担缓存和数据可见性规则。
     */
    private final RankingService rankingService;

    public RankingController(RankingService rankingService) {
        this.rankingService = rankingService;
    }

    /**
     * 查询热门资料榜。该公开路径已由 WebMvcConfig 放行，@Valid 先拦截超出 1 至 50 范围的数量和非法分类 ID。
     */
    @GetMapping("/resources/hot")
    public ApiResponse<List<HotResourceRankingVO>> listHotResources(
            @Valid HotResourceRankingQueryDTO query) {
        return ApiResponse.success(rankingService.listHotResources(query));
    }

    /**
     * 查询热门搜索词榜。周期白名单及不允许 all 周期的业务约束由 RankingService 统一处理。
     */
    @GetMapping("/search-keywords/hot")
    public ApiResponse<List<HotSearchKeywordRankingVO>> listHotSearchKeywords(
            @Valid HotSearchKeywordRankingQueryDTO query) {
        return ApiResponse.success(rankingService.listHotSearchKeywords(query));
    }
}
