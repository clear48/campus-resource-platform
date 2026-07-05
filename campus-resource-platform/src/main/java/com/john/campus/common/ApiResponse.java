package com.john.campus.common;

import java.util.UUID;

/**
 * 统一接口响应外壳：Controller 返回业务数据，ApiResponse 负责统一 code/message/data/traceId 协议。
 */
public record ApiResponse<T>(int code, String message, T data, String traceId) {

    /**
     * 成功响应统一使用 code=0，业务对象放入 data，便于前端只维护一种成功结构。
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data, newTraceId());
    }

    /**
     * 无业务数据的成功响应仍保持统一结构，避免 Controller 返回裸 null 或字符串。
     */
    public static ApiResponse<Void> success() {
        return success(null);
    }

    /**
     * 业务失败响应由全局异常处理器统一调用，保证异常场景也符合接口协议。
     */
    public static ApiResponse<Void> fail(ErrorCode errorCode) {
        return fail(errorCode.getCode(), errorCode.getMessage());
    }

    public static ApiResponse<Void> fail(int code, String message) {
        return new ApiResponse<>(code, message, null, newTraceId());
    }

    /**
     * 每次响应生成独立 traceId，后续接入日志链路时可用于定位一次具体请求。
     */
    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
