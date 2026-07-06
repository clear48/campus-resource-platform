package com.john.campus.service;

import com.john.campus.common.PageResult;
import com.john.campus.dto.AuditApproveDTO;
import com.john.campus.dto.AuditRejectDTO;
import com.john.campus.dto.PageQuery;
import com.john.campus.dto.ResourceOfflineDTO;
import com.john.campus.vo.AuditRecordVO;
import com.john.campus.vo.AuditResultVO;
import com.john.campus.vo.PendingReviewResourceVO;
import java.util.List;

/**
 * 审核业务接口，负责管理员审核资料时的状态流转和审计留痕。
 */
public interface AuditService {

    /**
     * 查询待审核资料分页列表，只允许管理员访问。
     */
    PageResult<PendingReviewResourceVO> listPendingReviews(
            String courseName,
            Integer resourceType,
            Long uploaderId,
            PageQuery pageQuery);

    /**
     * 审核通过待审核资料，并写入审核记录。
     */
    AuditResultVO approve(Long resourceId, AuditApproveDTO dto);

    /**
     * 审核拒绝待审核资料，并写入拒绝原因和审核记录。
     */
    AuditResultVO reject(Long resourceId, AuditRejectDTO dto);

    /**
     * 下架已通过资料，并写入下架原因和审核记录。
     */
    AuditResultVO offline(Long resourceId, ResourceOfflineDTO dto);

    /**
     * 查询某份资料的审核历史记录，只允许管理员访问。
     */
    List<AuditRecordVO> listAuditRecords(Long resourceId);
}
