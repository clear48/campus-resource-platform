package com.john.campus.mapper;

import com.john.campus.entity.Resource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 资料表数据访问接口，集中提供资料生命周期、公开查询和统计快照所需的数据访问能力。
 */
public interface ResourceMapper {

    /**
     * 插入资料记录，数据库自增主键会回填到 resource.id。
     */
    int insert(Resource resource);

    /**
     * 按 ID 查询资料原始记录，供 Service 层区分不存在和状态不可见。
     */
    Resource selectById(@Param("id") Long id);

    /**
     * 按资料 ID 批量查询记录，供收藏等列表接口补齐展示字段，调用方负责处理空集合。
     */
    List<Resource> selectByIds(@Param("ids") List<Long> ids);

    /**
     * 从排行榜候选资料中批量查询仍审核通过的记录，可按分类进一步过滤。
     * 返回顺序不保证与 Redis ZSet 一致，后续 Service 负责按 Redis 分数恢复榜单顺序。
     */
    List<Resource> selectApprovedRankingCandidatesByIds(
            @Param("ids") List<Long> ids,
            @Param("categoryId") Long categoryId);

    /**
     * Redis 不可用时按 MySQL 热度快照查询热门资料，固定排除非公开资料。
     */
    List<Resource> selectHotApprovedResources(
            @Param("categoryId") Long categoryId,
            @Param("limit") Integer limit);

    /**
     * 以主键游标分批扫描审核通过资料，用于重建 all 总榜；避免一次性把全部资料加载到应用内存。
     */
    List<Resource> selectApprovedResourcesAfterId(
            @Param("lastResourceId") Long lastResourceId,
            @Param("limit") Integer limit);

    /**
     * 查询公开资料详情，只返回审核通过的资料，避免未审核资料被匿名访问。
     */
    Resource selectPublicDetailById(@Param("id") Long id);

    /**
     * 按上传者查询资料列表，支持可选状态筛选，用于“我的上传资料”分页。
     */
    List<Resource> selectByUploader(
            @Param("uploaderId") Long uploaderId,
            @Param("status") Integer status,
            @Param("offset") Integer offset,
            @Param("pageSize") Integer pageSize);

    /**
     * 统计上传者资料数量，与 selectByUploader 使用同一筛选条件。
     */
    long countByUploader(@Param("uploaderId") Long uploaderId, @Param("status") Integer status);

    /**
     * 统计同一用户、同一文件下仍处于待审核或已通过状态的资料，用于防重复提交。
     */
    long countActiveByUploaderAndFileId(@Param("uploaderId") Long uploaderId, @Param("fileId") Long fileId);

    /**
     * 搜索公开资料列表，固定只返回 APPROVED 状态并支持搜索模块首版筛选条件。
     */
    List<Resource> searchApprovedResources(
            @Param("keyword") String keyword,
            @Param("categoryId") Long categoryId,
            @Param("courseName") String courseName,
            @Param("resourceType") Integer resourceType,
            @Param("tag") String tag,
            @Param("sortBy") String sortBy,
            @Param("order") String order,
            @Param("offset") Integer offset,
            @Param("pageSize") Integer pageSize);

    /**
     * 统计公开搜索结果数量，与 searchApprovedResources 使用完全一致的筛选条件。
     */
    long countApprovedResources(
            @Param("keyword") String keyword,
            @Param("categoryId") Long categoryId,
            @Param("courseName") String courseName,
            @Param("resourceType") Integer resourceType,
            @Param("tag") String tag);

    /**
     * 查询待审核资料列表，固定只返回 PENDING_REVIEW 状态并支持后台筛选。
     */
    List<Resource> selectPendingReviews(
            @Param("courseName") String courseName,
            @Param("resourceType") Integer resourceType,
            @Param("uploaderId") Long uploaderId,
            @Param("offset") Integer offset,
            @Param("pageSize") Integer pageSize);

    /**
     * 统计待审核资料数量，与 selectPendingReviews 使用完全一致的筛选条件。
     */
    long countPendingReviews(
            @Param("courseName") String courseName,
            @Param("resourceType") Integer resourceType,
            @Param("uploaderId") Long uploaderId);

    /**
     * 审核通过待审核资料，旧状态条件用于防止重复审核和并发状态覆盖。
     */
    int approvePendingReview(@Param("resourceId") Long resourceId, @Param("approvedAt") LocalDateTime approvedAt);

    /**
     * 审核拒绝待审核资料，只允许从 PENDING_REVIEW 状态流转到 REJECTED。
     */
    int rejectPendingReview(@Param("resourceId") Long resourceId, @Param("rejectReason") String rejectReason);

    /**
     * 下架已通过资料，只允许从 APPROVED 状态流转到 OFFLINE。
     */
    int offlineApprovedResource(
            @Param("resourceId") Long resourceId,
            @Param("offlineReason") String offlineReason,
            @Param("offlineAt") LocalDateTime offlineAt);

    /**
     * 原子调整资料收藏数，delta 仅允许收藏场景传入 +1 或 -1，避免读改写并发丢失。
     */
    int updateFavoriteCount(@Param("resourceId") Long resourceId, @Param("delta") int delta);

    /**
     * 原子累加下载次数，供后续 Redis 下载增量同步任务使用，避免读改写造成并发丢失。
     */
    int incrementDownloadCount(@Param("resourceId") Long resourceId, @Param("delta") long delta);

    /**
     * 更新审核通过资料的热度分快照；非公开资料不接受排行榜快照回写。
     */
    int updateApprovedHotScore(
            @Param("resourceId") Long resourceId,
            @Param("hotScore") BigDecimal hotScore);
}
