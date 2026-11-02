package com.john.campus.exception;

/**
 * 文件保护内部异常；仅携带稳定错误码，持久化审计时不保存绝对路径或底层异常文本。
 */
public class FileProtectionException extends RuntimeException {

    private final String errorCode;

    public FileProtectionException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public FileProtectionException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
