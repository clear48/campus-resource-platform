package com.john.campus.mapper;

import com.john.campus.entity.Category;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface CategoryMapper {

    /**
     * 查询指定父分类下的启用分类，供上传前分类选择使用。
     */
    List<Category> selectEnabledByParentId(@Param("parentId") Long parentId);
}
