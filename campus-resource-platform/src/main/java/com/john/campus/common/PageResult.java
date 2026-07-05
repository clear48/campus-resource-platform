package com.john.campus.common;

import java.util.List;

/**
 * 通用分页响应对象，保持列表接口的分页字段一致。
 */
public record PageResult<T>(List<T> records, long pageNo, long pageSize, long total, long pages) {

    /**
     * 根据总数和页大小计算总页数，避免各业务模块重复计算分页元数据。
     */
    public static <T> PageResult<T> of(List<T> records, long pageNo, long pageSize, long total) {
        // pageSize 非法时返回 0 页，避免除零并让调用方更容易发现参数问题。
        long pages = pageSize <= 0 ? 0 : (total + pageSize - 1) / pageSize;
        return new PageResult<>(records, pageNo, pageSize, total, pages);
    }
}
