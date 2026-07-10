package com.john.campus.controller;

import com.john.campus.common.ApiResponse;
import com.john.campus.common.PageResult;
import com.john.campus.dto.PageQuery;
import com.john.campus.service.FavoriteService;
import com.john.campus.vo.FavoriteResultVO;
import com.john.campus.vo.FavoriteStatusVO;
import com.john.campus.vo.MyFavoriteVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 收藏接口入口，只负责 HTTP 参数绑定与统一响应，收藏业务规则由 FavoriteService 处理。
 * 收藏路径不在 WebMvcConfig 放行列表中，全部由 JWT 拦截器保护。
 */
@RestController
@RequestMapping("/api/v1")
public class FavoriteController {

    private final FavoriteService favoriteService;

    public FavoriteController(FavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    /**
     * 收藏审核通过的资料；重复请求由 Service 按幂等成功处理。
     */
    @PostMapping("/resources/{resourceId}/favorites")
    public ApiResponse<FavoriteResultVO> favorite(@PathVariable Long resourceId) {
        return ApiResponse.success(favoriteService.favorite(resourceId));
    }

    /**
     * 取消当前登录用户对指定资料的有效收藏。
     */
    @DeleteMapping("/resources/{resourceId}/favorites")
    public ApiResponse<FavoriteResultVO> unfavorite(@PathVariable Long resourceId) {
        return ApiResponse.success(favoriteService.unfavorite(resourceId));
    }

    /**
     * 查询当前登录用户对指定资料的收藏状态。
     */
    @GetMapping("/resources/{resourceId}/favorite-status")
    public ApiResponse<FavoriteStatusVO> getFavoriteStatus(@PathVariable Long resourceId) {
        return ApiResponse.success(favoriteService.getFavoriteStatus(resourceId));
    }

    /**
     * 分页查询当前登录用户的有效收藏记录，不接受客户端传入 userId。
     */
    @GetMapping("/users/me/favorites")
    public ApiResponse<PageResult<MyFavoriteVO>> listMyFavorites(@Valid PageQuery pageQuery) {
        return ApiResponse.success(favoriteService.listMyFavorites(pageQuery));
    }
}
