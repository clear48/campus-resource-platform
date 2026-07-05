package com.john.campus.entity;

import lombok.Getter;
import lombok.Setter;

/**
 * 分类表实体，对应上传资料时可选择的课程资料分类。
 */
@Getter
@Setter
public class Category extends BaseEntity {

    /**
     * 禁用分类不会出现在上传前分类列表中，避免新增资料继续引用不可用分类。
     */
    public static final int STATUS_DISABLED = 0;
    /**
     * 只有启用分类才允许被分类查询接口返回。
     */
    public static final int STATUS_ENABLED = 1;

    /**
     * 父分类 ID，0 表示一级分类。
     */
    private Long parentId;
    /**
     * 分类展示名称。
     */
    private String categoryName;
    private String description;
    /**
     * 人工排序值，越小越靠前。
     */
    private Integer sortOrder;
    /**
     * 分类启用状态，查询接口只返回 STATUS_ENABLED。
     */
    private Integer status;

    /**
     * 集中封装启用状态判断，避免业务层散落魔法值比较。
     */
    public boolean isEnabled() {
        return Integer.valueOf(STATUS_ENABLED).equals(status);
    }
}
