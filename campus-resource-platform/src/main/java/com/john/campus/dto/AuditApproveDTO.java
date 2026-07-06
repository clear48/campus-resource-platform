package com.john.campus.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 审核通过请求参数，承载管理员可选填写的审核意见。
 */
@Getter
@Setter
public class AuditApproveDTO {

    /**
     * 审核意见允许为空；非空时限制长度，避免超过 audit_record.audit_reason 字段容量。
     */
    @Size(max = 500, message = "审核意见长度不能超过 500")
    private String auditReason;
}
