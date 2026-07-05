package com.john.campus.service;

import com.john.campus.vo.CategoryVO;
import java.util.List;

public interface CategoryService {

    /**
     * 按父分类查询启用分类，并转换为对外 VO，避免 Controller 直接暴露数据库实体。
     */
    List<CategoryVO> listEnabledCategoriesByParentId(Long parentId);
}
