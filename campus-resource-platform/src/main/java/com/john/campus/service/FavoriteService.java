package com.john.campus.service;

import com.john.campus.common.PageResult;
import com.john.campus.dto.PageQuery;
import com.john.campus.vo.FavoriteResultVO;
import com.john.campus.vo.FavoriteStatusVO;
import com.john.campus.vo.MyFavoriteVO;

/**
 * 收藏业务接口，负责收藏关系、收藏状态和我的收藏列表的业务编排。
 */
public interface FavoriteService {

    /**
     * 收藏审核通过的资料；重复请求按幂等成功处理。
     */
    FavoriteResultVO favorite(Long resourceId);

    /**
     * 取消当前用户的有效收藏，并同步减少资料收藏数。
     */
    FavoriteResultVO unfavorite(Long resourceId);

    /**
     * 查询当前登录用户是否收藏指定资料。
     */
    FavoriteStatusVO getFavoriteStatus(Long resourceId);

    /**
     * 分页查询当前登录用户的有效收藏列表。
     */
    PageResult<MyFavoriteVO> listMyFavorites(PageQuery pageQuery);
}
