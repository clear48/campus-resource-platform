package com.john.campus.mapper;

import com.john.campus.entity.Resource;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 资料表数据访问接口，当前仅提供资料模块首版所需的基础写入与查询能力。
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
}
