package com.john.campus.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 资料下架请求参数，承载管理员下架已通过资料时必须填写的原因。
 */
@Getter
@Setter
public class ResourceOfflineDTO {

    /**
     * 下架原因必须填写，便于后台审计和上传者理解资料退出公开链路的原因。
     */
    @NotBlank(message = "下架原因不能为空")
    @Size(max = 500, message = "下架原因长度不能超过 500")
    private String offlineReason;
}
