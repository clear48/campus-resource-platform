package com.john.campus.mapper;

import com.john.campus.entity.Category;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface CategoryMapper {

    /**
     * 按分类 ID 查询启用分类，供资料创建时校验 categoryId 是否可用。
     */
    Category selectEnabledById(@Param("id") Long id);

    /**
     * 查询指定父分类下的启用分类，供上传前分类选择使用。
     */
    List<Category> selectEnabledByParentId(@Param("parentId") Long parentId);
}
