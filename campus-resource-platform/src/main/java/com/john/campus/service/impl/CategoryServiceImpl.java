package com.john.campus.service.impl;

import com.john.campus.common.ErrorCode;
import com.john.campus.entity.Category;
import com.john.campus.exception.BusinessException;
import com.john.campus.mapper.CategoryMapper;
import com.john.campus.service.CategoryService;
import com.john.campus.vo.CategoryVO;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 分类查询业务实现，当前只提供公开只读的启用分类列表。
 */
@Service
public class CategoryServiceImpl implements CategoryService {

    /**
     * 约定 parentId=0 表示一级分类，和数据库设计、接口文档保持一致。
     */
    private static final long ROOT_PARENT_ID = 0L;

    /**
     * 分类表访问入口，具体 SQL 保持在 CategoryMapper.xml 中。
     */
    private final CategoryMapper categoryMapper;

    public CategoryServiceImpl(CategoryMapper categoryMapper) {
        this.categoryMapper = categoryMapper;
    }

    /**
     * 查询指定父分类下的启用分类，返回给前端作为上传前的可选分类。
     */
    @Override
    public List<CategoryVO> listEnabledCategoriesByParentId(Long parentId) {
        // 不传 parentId 时默认查一级分类，方便前端首次进入上传页直接加载根分类。
        Long queryParentId = parentId == null ? ROOT_PARENT_ID : parentId;
        if (queryParentId < ROOT_PARENT_ID) {
            // 负数父分类没有业务含义，提前拒绝可以避免无意义 SQL 查询。
            throw new BusinessException(ErrorCode.PARAM_ERROR, "parentId 不能小于 0");
        }

        // 上传前只能选择启用分类，避免新资料继续引用已停用的分类节点。
        return categoryMapper.selectEnabledByParentId(queryParentId)
                .stream()
                .map(this::toCategoryVO)
                .toList();
    }

    /**
     * Entity 到 VO 的转换集中放在 Service 层，接口只暴露展示字段。
     */
    private CategoryVO toCategoryVO(Category category) {
        return new CategoryVO(
                category.getId(),
                category.getParentId(),
                category.getCategoryName(),
                category.getDescription(),
                category.getSortOrder()
        );
    }
}
