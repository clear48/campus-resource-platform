package com.john.campus.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 搜索资料请求参数，承载公开搜索的筛选、排序和分页条件。
 */
@Getter
@Setter
public class SearchResourceQueryDTO extends PageQuery {

    /**
     * 搜索关键词，后续 Service 会 trim 后匹配标题、简介、课程名和标签。
     */
    @Size(max = 100, message = "搜索关键词长度不能超过 100")
    private String keyword;

    /**
     * 分类 ID 筛选条件，只允许正数，避免无意义的数据库查询。
     */
    @Positive(message = "分类 ID 必须大于 0")
    private Long categoryId;

    /**
     * 课程名称筛选条件，长度与 resource.course_name 字段保持一致。
     */
    @Size(max = 100, message = "课程名称长度不能超过 100")
    private String courseName;

    /**
     * 资料类型筛选条件：1课件 2笔记 3真题 4实验报告 5课程设计 99其他。
     */
    private Integer resourceType;

    /**
     * 标签筛选条件，长度与单个标签限制保持一致。
     */
    @Size(max = 20, message = "标签长度不能超过 20")
    private String tag;

    /**
     * 排序字段只能使用白名单名称，Service 会映射为真实数据库列名。
     */
    @Pattern(
            regexp = "createdAt|downloadCount|favoriteCount|hotScore",
            message = "排序字段不合法")
    private String sortBy = "createdAt";

    /**
     * 排序方向只允许 asc 或 desc，避免后续拼接排序 SQL 时出现注入风险。
     */
    @Pattern(regexp = "(?i)asc|desc", message = "排序方向不合法")
    private String order = "desc";

    /**
     * Validation 扩展校验：资料类型只能取业务允许的枚举值。
     */
    @AssertTrue(message = "资料类型不合法")
    public boolean isResourceTypeValid() {
        if (resourceType == null) {
            return true;
        }
        return resourceType == 1
                || resourceType == 2
                || resourceType == 3
                || resourceType == 4
                || resourceType == 5
                || resourceType == 99;
    }
}
