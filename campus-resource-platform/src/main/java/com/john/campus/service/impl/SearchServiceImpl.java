package com.john.campus.service.impl;

import com.john.campus.common.ErrorCode;
import com.john.campus.common.PageResult;
import com.john.campus.dto.SearchResourceQueryDTO;
import com.john.campus.entity.Resource;
import com.john.campus.exception.BusinessException;
import com.john.campus.mapper.ResourceMapper;
import com.john.campus.service.SearchService;
import com.john.campus.vo.SearchResourceVO;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 搜索业务实现，首版基于 MySQL 查询审核通过资料。
 */
@Service
public class SearchServiceImpl implements SearchService {

    /**
     * 搜索参数长度限制与 DTO、数据库字段和当前搜索设计保持一致。
     */
    private static final int MAX_KEYWORD_LENGTH = 100;
    private static final int MAX_COURSE_NAME_LENGTH = 100;
    private static final int MAX_TAG_LENGTH = 20;
    private static final int DEFAULT_PAGE_NO = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String DEFAULT_SORT_BY = "createdAt";
    private static final String DEFAULT_ORDER = "desc";
    private static final String ORDER_ASC = "asc";
    private static final String ORDER_DESC = "desc";
    private static final String SORT_BY_CREATED_AT = "createdAt";
    private static final String SORT_BY_DOWNLOAD_COUNT = "downloadCount";
    private static final String SORT_BY_FAVORITE_COUNT = "favoriteCount";
    private static final String SORT_BY_HOT_SCORE = "hotScore";

    /**
     * 资料 Mapper 只负责执行安全 SQL，业务参数归一化和兜底校验放在 Service 层。
     */
    private final ResourceMapper resourceMapper;

    public SearchServiceImpl(ResourceMapper resourceMapper) {
        this.resourceMapper = resourceMapper;
    }

    /**
     * 搜索公开资料主流程：校验参数、调用 Mapper、转换 VO，不在首版写入 Redis。
     */
    @Override
    public PageResult<SearchResourceVO> searchResources(SearchResourceQueryDTO query) {
        ResolvedSearchQuery resolvedQuery = resolveSearchQuery(query);
        long total = resourceMapper.countApprovedResources(
                resolvedQuery.keyword(),
                resolvedQuery.categoryId(),
                resolvedQuery.courseName(),
                resolvedQuery.resourceType(),
                resolvedQuery.tag());
        if (total == 0) {
            return PageResult.of(List.of(), resolvedQuery.pageNo(), resolvedQuery.pageSize(), total);
        }

        List<Resource> resources = resourceMapper.searchApprovedResources(
                resolvedQuery.keyword(),
                resolvedQuery.categoryId(),
                resolvedQuery.courseName(),
                resolvedQuery.resourceType(),
                resolvedQuery.tag(),
                resolvedQuery.sortBy(),
                resolvedQuery.order(),
                resolvedQuery.offset(),
                resolvedQuery.pageSize());
        List<SearchResourceVO> records = resources.stream()
                .map(this::toSearchResourceVO)
                .toList();
        return PageResult.of(records, resolvedQuery.pageNo(), resolvedQuery.pageSize(), total);
    }

    /**
     * 解析并校验搜索条件，确保后续传给 Mapper 的值都已归一化。
     */
    private ResolvedSearchQuery resolveSearchQuery(SearchResourceQueryDTO query) {
        SearchResourceQueryDTO safeQuery = query == null ? new SearchResourceQueryDTO() : query;
        String keyword = trimToNull(safeQuery.getKeyword());
        String courseName = trimToNull(safeQuery.getCourseName());
        String tag = trimToNull(safeQuery.getTag());
        Long categoryId = safeQuery.getCategoryId();
        Integer resourceType = safeQuery.getResourceType();
        int pageNo = resolvePageNo(safeQuery.getPageNo());
        int pageSize = resolvePageSize(safeQuery.getPageSize());
        String sortBy = resolveSortBy(safeQuery.getSortBy());
        String order = resolveOrder(safeQuery.getOrder());

        validateTextLength(keyword, MAX_KEYWORD_LENGTH, "搜索关键词长度不能超过 100");
        validateTextLength(courseName, MAX_COURSE_NAME_LENGTH, "课程名称长度不能超过 100");
        validateTextLength(tag, MAX_TAG_LENGTH, "标签长度不能超过 20");
        validatePositive(categoryId, "分类 ID 必须大于 0");
        validateResourceType(resourceType);

        int offset = (pageNo - 1) * pageSize;
        return new ResolvedSearchQuery(
                keyword,
                categoryId,
                courseName,
                resourceType,
                tag,
                sortBy,
                order,
                pageNo,
                pageSize,
                offset);
    }

    /**
     * 校验普通文本长度，空值不参与搜索条件。
     */
    private void validateTextLength(String value, int maxLength, String message) {
        if (value != null && value.length() > maxLength) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, message);
        }
    }

    /**
     * 正数校验用于分类 ID 等业务标识，避免无意义查询进入 Mapper。
     */
    private void validatePositive(Long value, String message) {
        if (value != null && value <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, message);
        }
    }

    /**
     * 资料类型必须落在 resource 表允许的业务枚举范围内。
     */
    private void validateResourceType(Integer resourceType) {
        if (resourceType == null) {
            return;
        }
        boolean valid = Resource.TYPE_COURSEWARE == resourceType
                || Resource.TYPE_NOTE == resourceType
                || Resource.TYPE_EXAM == resourceType
                || Resource.TYPE_LAB_REPORT == resourceType
                || Resource.TYPE_COURSE_DESIGN == resourceType
                || Resource.TYPE_OTHER == resourceType;
        if (!valid) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "资料类型不合法");
        }
    }

    /**
     * 页码从 1 开始，兼容 Controller 未接入 Validation 时的直接 Service 调用。
     */
    private int resolvePageNo(Integer pageNo) {
        if (pageNo == null) {
            return DEFAULT_PAGE_NO;
        }
        if (pageNo < DEFAULT_PAGE_NO) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "pageNo 必须大于等于 1");
        }
        return pageNo;
    }

    /**
     * 单页最大 100 条，保护数据库查询和响应体大小。
     */
    private int resolvePageSize(Integer pageSize) {
        if (pageSize == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "pageSize 必须在 1 到 100 之间");
        }
        return pageSize;
    }

    /**
     * 排序字段归一化为 Mapper XML 支持的白名单名称。
     */
    private String resolveSortBy(String sortBy) {
        if (!StringUtils.hasText(sortBy)) {
            return DEFAULT_SORT_BY;
        }
        String normalizedSortBy = sortBy.trim();
        boolean valid = SORT_BY_CREATED_AT.equals(normalizedSortBy)
                || SORT_BY_DOWNLOAD_COUNT.equals(normalizedSortBy)
                || SORT_BY_FAVORITE_COUNT.equals(normalizedSortBy)
                || SORT_BY_HOT_SCORE.equals(normalizedSortBy);
        if (!valid) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "排序字段不合法");
        }
        return normalizedSortBy;
    }

    /**
     * 排序方向统一转小写，后续 Mapper 只接收 asc 或 desc。
     */
    private String resolveOrder(String order) {
        if (!StringUtils.hasText(order)) {
            return DEFAULT_ORDER;
        }
        String normalizedOrder = order.trim().toLowerCase(Locale.ROOT);
        if (!ORDER_ASC.equals(normalizedOrder) && !ORDER_DESC.equals(normalizedOrder)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "排序方向不合法");
        }
        return normalizedOrder;
    }

    /**
     * 空白字符串统一转 null，避免把无效条件传入动态 SQL。
     */
    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /**
     * 将数据库逗号分隔标签转换为前端使用的列表结构。
     */
    private List<String> splitTags(String tags) {
        if (!StringUtils.hasText(tags)) {
            return List.of();
        }
        return Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    /**
     * 搜索列表响应只暴露公开展示字段和统计快照。
     */
    private SearchResourceVO toSearchResourceVO(Resource resource) {
        return new SearchResourceVO(
                resource.getId(),
                resource.getTitle(),
                resource.getDescription(),
                resource.getCourseName(),
                resource.getResourceType(),
                splitTags(resource.getTags()),
                resource.getDownloadCount(),
                resource.getFavoriteCount(),
                resource.getHotScore(),
                resource.getCreatedAt());
    }

    /**
     * 已完成兜底校验和归一化的查询条件，避免在主流程里传递过长参数列表。
     */
    private record ResolvedSearchQuery(
            String keyword,
            Long categoryId,
            String courseName,
            Integer resourceType,
            String tag,
            String sortBy,
            String order,
            int pageNo,
            int pageSize,
            int offset) {
    }
}
