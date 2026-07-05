package com.john.campus.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 通用分页请求参数，后续列表接口可以继承或组合使用。
 */
public class PageQuery {

    /**
     * 页码从 1 开始，避免前端和后端对 0 基页码产生歧义。
     */
    @Min(value = 1, message = "pageNo 必须大于等于 1")
    private Integer pageNo = 1;

    /**
     * 单页最大 100 条，防止一次请求拉取过多数据影响数据库和响应体大小。
     */
    @Min(value = 1, message = "pageSize 必须大于等于 1")
    @Max(value = 100, message = "pageSize 不能大于 100")
    private Integer pageSize = 10;

    public Integer getPageNo() {
        return pageNo;
    }

    public void setPageNo(Integer pageNo) {
        this.pageNo = pageNo;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }

    /**
     * MySQL 分页偏移量，Mapper 拼分页 SQL 时可直接复用。
     */
    public int offset() {
        return (pageNo - 1) * pageSize;
    }
}
