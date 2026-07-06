package com.john.campus.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 审核拒绝请求参数，拒绝原因会同时写入 resource 和 audit_record。
 */
@Getter
@Setter
public class AuditRejectDTO {

    /**
     * 拒绝原因必须填写，上传者后续需要通过该原因理解如何修正资料。
     */
    @NotBlank(message = "拒绝原因不能为空")
    @Size(max = 500, message = "拒绝原因长度不能超过 500")
    private String rejectReason;
}
