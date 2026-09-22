package com.john.campus.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.john.campus.common.ApiResponse;
import com.john.campus.common.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 全局异常 HTTP 语义测试。
 */
class GlobalExceptionHandlerTest {

    @Test
    void insufficientStorageShouldMapToHttp507() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ApiResponse<Void>> response = handler.handleBusinessException(
                new BusinessException(ErrorCode.STORAGE_INSUFFICIENT));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INSUFFICIENT_STORAGE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCode.STORAGE_INSUFFICIENT.getCode());
    }
}
