package com.john.campus.controller;

import com.john.campus.common.ApiResponse;
import com.john.campus.common.PageResult;
import com.john.campus.dto.AuditApproveDTO;
import com.john.campus.dto.AuditRejectDTO;
import com.john.campus.dto.PageQuery;
import com.john.campus.dto.ResourceOfflineDTO;
import com.john.campus.service.AuditService;
import com.john.campus.vo.AuditRecordVO;
import com.john.campus.vo.AuditResultVO;
import com.john.campus.vo.PendingReviewResourceVO;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员审核接口入口，只负责参数绑定和响应包装，审核状态机交给 AuditService。
 */
@RestController
@RequestMapping("/api/v1/admin/resources")
public class AuditController {

    /**
     * 只允许浏览器内联展示内容风险较低的格式；其余类型统一按二进制附件下载。
     */
    private static final Map<String, MediaType> INLINE_REVIEW_TYPES = Map.of(
            "pdf", MediaType.APPLICATION_PDF,
            "jpg", MediaType.IMAGE_JPEG,
            "jpeg", MediaType.IMAGE_JPEG,
            "png", MediaType.IMAGE_PNG,
            "txt", MediaType.TEXT_PLAIN,
            "md", MediaType.TEXT_PLAIN);

    /**
     * 审核业务服务，Controller 不直接访问 Mapper，避免请求层承载状态流转规则。
     */
    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * 查询待审核资料列表：登录和管理员身份由拦截器与 Service 共同兜底。
     */
    @GetMapping("/pending-reviews")
    public ApiResponse<PageResult<PendingReviewResourceVO>> listPendingReviews(
            @RequestParam(required = false) String courseName,
            @RequestParam(required = false) Integer resourceType,
            @RequestParam(required = false) Long uploaderId,
            @Valid PageQuery pageQuery) {
        return ApiResponse.success(auditService.listPendingReviews(courseName, resourceType, uploaderId, pageQuery));
    }

    /**
     * 审核通过资料：审核意见可选，因此允许请求体为空，由 Service 统一按 null 处理。
     */
    @PostMapping("/{resourceId}/audit-approvals")
    public ApiResponse<AuditResultVO> approve(
            @PathVariable Long resourceId,
            @Valid @RequestBody(required = false) AuditApproveDTO request) {
        return ApiResponse.success(auditService.approve(resourceId, request));
    }

    /**
     * 审核拒绝资料：拒绝原因必须在 DTO Validation 和 Service 兜底校验中同时保证。
     */
    @PostMapping("/{resourceId}/audit-rejections")
    public ApiResponse<AuditResultVO> reject(
            @PathVariable Long resourceId,
            @Valid @RequestBody AuditRejectDTO request) {
        return ApiResponse.success(auditService.reject(resourceId, request));
    }

    /**
     * 下架资料：只暴露管理员接口，具体状态是否合法由 Service 状态机判断。
     */
    @PostMapping("/{resourceId}/offline-records")
    public ApiResponse<AuditResultVO> offline(
            @PathVariable Long resourceId,
            @Valid @RequestBody ResourceOfflineDTO request) {
        return ApiResponse.success(auditService.offline(resourceId, request));
    }

    /**
     * 查询资料审核记录：用于后台追踪某份资料的完整审核历史。
     */
    @GetMapping("/{resourceId}/audit-records")
    public ApiResponse<List<AuditRecordVO>> listAuditRecords(@PathVariable Long resourceId) {
        return ApiResponse.success(auditService.listAuditRecords(resourceId));
    }

    /**
     * 管理员读取待审核资料实际文件；响应禁止缓存，并由服务端扩展名白名单决定能否内联预览。
     */
    @GetMapping("/{resourceId}/review-file")
    public ResponseEntity<InputStreamResource> reviewFile(@PathVariable Long resourceId) {
        AuditService.ReviewFileInfo fileInfo = auditService.loadReviewFile(resourceId);
        String fileExt = fileInfo.fileExt() == null
                ? ""
                : fileInfo.fileExt().toLowerCase(Locale.ROOT);
        MediaType mediaType = INLINE_REVIEW_TYPES.getOrDefault(fileExt, MediaType.APPLICATION_OCTET_STREAM);
        String originalName = fileInfo.originalName() == null || fileInfo.originalName().isBlank()
                ? buildFallbackFileName(resourceId, fileExt)
                : fileInfo.originalName();
        ContentDisposition disposition = INLINE_REVIEW_TYPES.containsKey(fileExt)
                ? ContentDisposition.inline().filename(originalName, StandardCharsets.UTF_8).build()
                : ContentDisposition.attachment().filename(originalName, StandardCharsets.UTF_8).build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaType);
        headers.setContentDisposition(disposition);
        headers.setContentLength(fileInfo.contentLength());
        headers.setCacheControl("private, no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        return new ResponseEntity<>(new InputStreamResource(fileInfo.inputStream()), headers, HttpStatus.OK);
    }

    private String buildFallbackFileName(Long resourceId, String fileExt) {
        return fileExt.isBlank()
                ? "resource-" + resourceId
                : "resource-" + resourceId + "." + fileExt;
    }
}
