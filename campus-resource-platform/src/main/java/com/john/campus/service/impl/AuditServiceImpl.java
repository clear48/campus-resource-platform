package com.john.campus.service.impl;

import com.john.campus.common.ErrorCode;
import com.john.campus.common.LoginUser;
import com.john.campus.common.PageResult;
import com.john.campus.common.UserContextHolder;
import com.john.campus.dto.AuditApproveDTO;
import com.john.campus.dto.AuditRejectDTO;
import com.john.campus.dto.PageQuery;
import com.john.campus.dto.ResourceOfflineDTO;
import com.john.campus.entity.AuditRecord;
import com.john.campus.entity.Resource;
import com.john.campus.exception.BusinessException;
import com.john.campus.mapper.AuditRecordMapper;
import com.john.campus.mapper.ResourceMapper;
import com.john.campus.service.AuditService;
import com.john.campus.service.RankingService;
import com.john.campus.vo.AuditRecordVO;
import com.john.campus.vo.AuditResultVO;
import com.john.campus.vo.PendingReviewResourceVO;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

/**
 * 审核业务实现，集中处理管理员权限、资料状态机、事务和审核记录写入。
 */
@Service
public class AuditServiceImpl implements AuditService {

    /**
     * 记录排行榜派生数据的失败信息，便于排查 Redis 故障，同时不干扰审核主事务。
     */
    private static final Logger log = LoggerFactory.getLogger(AuditServiceImpl.class);

    /**
     * 审核原因字段与 resource/audit_record 表的 VARCHAR(500) 保持一致。
     */
    private static final int MAX_REASON_LENGTH = 500;
    private static final int DEFAULT_PAGE_NO = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * 资料表访问入口，用于查询待审核资料和执行状态流转。
     */
    private final ResourceMapper resourceMapper;
    /**
     * 审核记录访问入口，用于写入和查询审计流水。
     */
    private final AuditRecordMapper auditRecordMapper;
    /**
     * 审核提交后维护排行榜成员；不能在事务内直接写 Redis，否则数据库回滚会留下错误榜单状态。
     */
    private final RankingService rankingService;

    public AuditServiceImpl(
            ResourceMapper resourceMapper,
            AuditRecordMapper auditRecordMapper,
            RankingService rankingService) {
        this.resourceMapper = resourceMapper;
        this.auditRecordMapper = auditRecordMapper;
        this.rankingService = rankingService;
    }

    /**
     * 管理员待审核列表：只读取 status=PENDING_REVIEW 的资料，不改变业务状态。
     */
    @Override
    public PageResult<PendingReviewResourceVO> listPendingReviews(
            String courseName,
            Integer resourceType,
            Long uploaderId,
            PageQuery pageQuery) {
        requireAdmin();
        validateResourceType(resourceType);
        validateUploaderId(uploaderId);
        int pageNo = resolvePageNo(pageQuery);
        int pageSize = resolvePageSize(pageQuery);
        int offset = (pageNo - 1) * pageSize;
        String normalizedCourseName = trimToNull(courseName);

        List<Resource> resources = resourceMapper.selectPendingReviews(
                normalizedCourseName, resourceType, uploaderId, offset, pageSize);
        long total = resourceMapper.countPendingReviews(normalizedCourseName, resourceType, uploaderId);
        List<PendingReviewResourceVO> records = resources.stream()
                .map(this::toPendingReviewResourceVO)
                .toList();
        return PageResult.of(records, pageNo, pageSize, total);
    }

    /**
     * 审核通过状态机：待审核 -> 已通过，并在同一事务内写入审核记录。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AuditResultVO approve(Long resourceId, AuditApproveDTO dto) {
        LoginUser admin = requireAdmin();
        validateResourceId(resourceId);
        String auditReason = trimToNull(dto == null ? null : dto.getAuditReason());
        validateOptionalReason(auditReason, "审核意见长度不能超过 500");

        Resource resource = requireResource(resourceId);
        if (!resource.isPendingReview()) {
            throw new BusinessException(ErrorCode.RESOURCE_STATUS_INVALID, "只有待审核资料可以审核通过");
        }

        LocalDateTime approvedAt = LocalDateTime.now();
        int updatedRows = resourceMapper.approvePendingReview(resourceId, approvedAt);
        if (updatedRows == 0) {
            // SQL 旧状态条件未命中时，说明资料被并发审核或状态已不允许当前动作。
            throw new BusinessException(ErrorCode.RESOURCE_STATUS_INVALID, "资料状态已变化，无法审核通过");
        }

        AuditRecord auditRecord = insertAuditRecord(
                resourceId,
                admin.userId(),
                AuditRecord.ACTION_APPROVE,
                resource.getStatus(),
                Resource.STATUS_APPROVED,
                auditReason);
        runAfterCommit(() -> rankingService.initializeApprovedResource(resourceId));
        return toAuditResultVO(auditRecord, approvedAt, null);
    }

    /**
     * 审核拒绝状态机：待审核 -> 已拒绝，并保存拒绝原因方便上传者查看。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AuditResultVO reject(Long resourceId, AuditRejectDTO dto) {
        LoginUser admin = requireAdmin();
        validateResourceId(resourceId);
        String rejectReason = trimToNull(dto == null ? null : dto.getRejectReason());
        validateRequiredReason(rejectReason, "拒绝原因不能为空", "拒绝原因长度不能超过 500");

        Resource resource = requireResource(resourceId);
        if (!resource.isPendingReview()) {
            throw new BusinessException(ErrorCode.RESOURCE_STATUS_INVALID, "只有待审核资料可以审核拒绝");
        }

        int updatedRows = resourceMapper.rejectPendingReview(resourceId, rejectReason);
        if (updatedRows == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_STATUS_INVALID, "资料状态已变化，无法审核拒绝");
        }

        AuditRecord auditRecord = insertAuditRecord(
                resourceId,
                admin.userId(),
                AuditRecord.ACTION_REJECT,
                resource.getStatus(),
                Resource.STATUS_REJECTED,
                rejectReason);
        return toAuditResultVO(auditRecord, null, null);
    }

    /**
     * 下架状态机：已通过 -> 已下架，并保留最近下架原因和审计流水。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AuditResultVO offline(Long resourceId, ResourceOfflineDTO dto) {
        LoginUser admin = requireAdmin();
        validateResourceId(resourceId);
        String offlineReason = trimToNull(dto == null ? null : dto.getOfflineReason());
        validateRequiredReason(offlineReason, "下架原因不能为空", "下架原因长度不能超过 500");

        Resource resource = requireResource(resourceId);
        if (!resource.isApproved()) {
            throw new BusinessException(ErrorCode.RESOURCE_STATUS_INVALID, "只有已通过资料可以下架");
        }

        LocalDateTime offlineAt = LocalDateTime.now();
        int updatedRows = resourceMapper.offlineApprovedResource(resourceId, offlineReason, offlineAt);
        if (updatedRows == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_STATUS_INVALID, "资料状态已变化，无法下架");
        }

        AuditRecord auditRecord = insertAuditRecord(
                resourceId,
                admin.userId(),
                AuditRecord.ACTION_OFFLINE,
                resource.getStatus(),
                Resource.STATUS_OFFLINE,
                offlineReason);
        runAfterCommit(() -> rankingService.removeOfflineResource(resourceId));
        return toAuditResultVO(auditRecord, null, offlineAt);
    }

    /**
     * 仅在 MySQL 事务真正提交后执行 Redis 派生数据更新；未开启事务的测试或内部调用则立即执行，便于保持调用语义一致。
     */
    private void runAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    action.run();
                } catch (RuntimeException ex) {
                    // Redis 派生数据异常不应影响已提交的审核状态机结果。
                    log.warn("审核完成后同步排行榜失败", ex);
                }
            }
        });
    }

    /**
     * 审核历史查询先确认资料存在，再返回对应 audit_record 流水。
     */
    @Override
    public List<AuditRecordVO> listAuditRecords(Long resourceId) {
        requireAdmin();
        validateResourceId(resourceId);
        requireResource(resourceId);
        return auditRecordMapper.selectByResourceId(resourceId).stream()
                .map(this::toAuditRecordVO)
                .toList();
    }

    /**
     * 管理员权限来自 JWT 写入的 LoginUser，不能接受前端传 role 参数。
     */
    private LoginUser requireAdmin() {
        LoginUser loginUser = UserContextHolder.getRequired();
        if (!loginUser.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return loginUser;
    }

    /**
     * 资料 ID 是审核状态机的入口，必须在访问数据库前做基础合法性校验。
     */
    private void validateResourceId(Long resourceId) {
        if (resourceId == null || resourceId <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "资料 ID 不合法");
        }
    }

    /**
     * 待审核列表的上传者筛选不能小于等于 0，避免无意义查询。
     */
    private void validateUploaderId(Long uploaderId) {
        if (uploaderId != null && uploaderId <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "上传者 ID 不合法");
        }
    }

    /**
     * 资料类型筛选必须与 resource 表 CHECK 约束保持一致。
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
     * 查询资料并统一处理不存在场景，避免各审核动作重复写判断。
     */
    private Resource requireResource(Long resourceId) {
        Resource resource = resourceMapper.selectById(resourceId);
        if (resource == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "资料不存在");
        }
        return resource;
    }

    /**
     * 可选原因只校验长度，适合审核通过意见。
     */
    private void validateOptionalReason(String reason, String lengthMessage) {
        if (reason != null && reason.length() > MAX_REASON_LENGTH) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, lengthMessage);
        }
    }

    /**
     * 必填原因同时校验非空和长度，适合拒绝、下架这类必须说明原因的动作。
     */
    private void validateRequiredReason(String reason, String blankMessage, String lengthMessage) {
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, blankMessage);
        }
        validateOptionalReason(reason, lengthMessage);
    }

    /**
     * 解析页码，Service 层保留兜底校验，防止绕过 Controller Validation。
     */
    private int resolvePageNo(PageQuery pageQuery) {
        Integer pageNo = pageQuery == null ? DEFAULT_PAGE_NO : pageQuery.getPageNo();
        if (pageNo == null) {
            return DEFAULT_PAGE_NO;
        }
        if (pageNo < DEFAULT_PAGE_NO) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "pageNo 必须大于等于 1");
        }
        return pageNo;
    }

    /**
     * 解析页大小，限制最大值保护后台审核列表查询和响应体大小。
     */
    private int resolvePageSize(PageQuery pageQuery) {
        Integer pageSize = pageQuery == null ? DEFAULT_PAGE_SIZE : pageQuery.getPageSize();
        if (pageSize == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "pageSize 必须在 1 到 100 之间");
        }
        return pageSize;
    }

    /**
     * 写入审核记录，所有审核动作都复用同一条审计落库路径。
     */
    private AuditRecord insertAuditRecord(
            Long resourceId,
            Long auditorId,
            Integer actionType,
            Integer beforeStatus,
            Integer afterStatus,
            String auditReason) {
        AuditRecord auditRecord = new AuditRecord();
        auditRecord.setResourceId(resourceId);
        auditRecord.setAuditorId(auditorId);
        auditRecord.setActionType(actionType);
        auditRecord.setBeforeStatus(beforeStatus);
        auditRecord.setAfterStatus(afterStatus);
        auditRecord.setAuditReason(auditReason);
        auditRecordMapper.insert(auditRecord);
        return auditRecord;
    }

    /**
     * 空白字符串统一转 null，便于 Mapper 可选条件和数据库字段保持干净。
     */
    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /**
     * 将数据库逗号分隔标签转换为列表结构，审核列表不直接暴露内部存储格式。
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
     * 待审核列表响应只包含管理员审核决策需要的字段，不直接返回 Entity。
     */
    private PendingReviewResourceVO toPendingReviewResourceVO(Resource resource) {
        return new PendingReviewResourceVO(
                resource.getId(),
                resource.getTitle(),
                resource.getDescription(),
                resource.getCategoryId(),
                resource.getCourseName(),
                resource.getResourceType(),
                splitTags(resource.getTags()),
                resource.getFileId(),
                resource.getUploaderId(),
                resource.getStatus(),
                resource.getCreatedAt()
        );
    }

    /**
     * 审核动作结果统一由审核记录和动作时间组装，保证通过/拒绝/下架响应结构一致。
     */
    private AuditResultVO toAuditResultVO(AuditRecord auditRecord, LocalDateTime approvedAt, LocalDateTime offlineAt) {
        return new AuditResultVO(
                auditRecord.getResourceId(),
                auditRecord.getActionType(),
                auditRecord.getBeforeStatus(),
                auditRecord.getAfterStatus(),
                auditRecord.getId(),
                auditRecord.getAuditReason(),
                approvedAt,
                offlineAt
        );
    }

    /**
     * 审核历史响应不暴露 AuditRecord Entity，避免接口层依赖数据库实体。
     */
    private AuditRecordVO toAuditRecordVO(AuditRecord auditRecord) {
        return new AuditRecordVO(
                auditRecord.getId(),
                auditRecord.getResourceId(),
                auditRecord.getAuditorId(),
                auditRecord.getActionType(),
                auditRecord.getBeforeStatus(),
                auditRecord.getAfterStatus(),
                auditRecord.getAuditReason(),
                auditRecord.getCreatedAt()
        );
    }
}
