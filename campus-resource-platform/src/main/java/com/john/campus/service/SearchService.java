package com.john.campus.service;

import com.john.campus.common.PageResult;
import com.john.campus.dto.SearchResourceQueryDTO;
import com.john.campus.vo.SearchResourceVO;

/**
 * 搜索业务接口，负责公开资料检索入口的业务编排。
 */
public interface SearchService {

    /**
     * 搜索公开资料，只返回审核通过资料并返回分页列表。
     */
    PageResult<SearchResourceVO> searchResources(SearchResourceQueryDTO query);
}
