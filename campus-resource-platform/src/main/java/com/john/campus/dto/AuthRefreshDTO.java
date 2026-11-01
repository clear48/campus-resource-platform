package com.john.campus.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 刷新或退出所用的独立凭据；不使用 Access Token 作为刷新凭据。 */
public record AuthRefreshDTO(@NotBlank @Size(max = 128) String refreshToken) {
}
