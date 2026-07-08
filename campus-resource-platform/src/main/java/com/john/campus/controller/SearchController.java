package com.john.campus.controller;

import com.john.campus.common.ApiResponse;
import com.john.campus.common.PageResult;
import com.john.campus.dto.SearchResourceQueryDTO;
import com.john.campus.service.SearchService;
import com.john.campus.vo.SearchResourceVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 搜索接口入口，负责接收公开搜索请求并把业务编排交给 SearchService。
 */
@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    /**
     * 搜索业务服务，Controller 不直接访问 Mapper，可见性过滤和排序白名单都在 Service 层。
     */
    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    /**
     * 搜索公开资料：该路径在 WebMvcConfig 中匿名放行，Service 强制只返回审核通过资料。
     * 查询参数由 SearchResourceQueryDTO 绑定，@Valid 触发关键词长度、分类 ID、排序等校验。
     */
    @GetMapping("/resources")
    public ApiResponse<PageResult<SearchResourceVO>> searchResources(
            @Valid SearchResourceQueryDTO query) {
        return ApiResponse.success(searchService.searchResources(query));
    }
}
