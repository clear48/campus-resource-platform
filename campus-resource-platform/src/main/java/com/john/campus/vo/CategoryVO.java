package com.john.campus.vo;

/**
 * 分类查询响应对象，只返回前端展示和选择分类所需字段。
 */
public record CategoryVO(
        Long categoryId,
        Long parentId,
        String categoryName,
        String description,
        Integer sortOrder
) {
}
