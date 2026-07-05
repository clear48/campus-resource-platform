package com.john.campus.exception;

import com.john.campus.common.ErrorCode;

/**
 * 业务异常，携带项目统一错误码，交由 GlobalExceptionHandler 转成统一响应。
 */
public class BusinessException extends RuntimeException {

    /**
     * 对外返回的业务错误码。
     */
    private final int code;

    /**
     * 使用错误码默认提示信息。
     */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    /**
     * 使用错误码但覆盖更具体的业务提示。
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }

    public int getCode() {
        return code;
    }
}
