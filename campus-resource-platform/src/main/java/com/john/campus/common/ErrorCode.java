package com.john.campus.common;

/**
 * 全局业务错误码，统一约定接口响应 code 和 message。
 */
public enum ErrorCode {
    /**
     * 请求成功，前端可统一用 code=0 判断成功分支。
     */
    SUCCESS(0, "success"),

    /**
     * 4xxxx 表示客户端请求或业务状态问题。
     */
    PARAM_ERROR(40001, "参数不合法"),
    DATA_DUPLICATE(40002, "数据重复"),
    UNAUTHORIZED(40101, "未登录或 Token 无效"),
    TOKEN_BLACKLISTED(40102, "Token 已失效"),
    FORBIDDEN(40301, "无权限访问"),
    RESOURCE_NOT_FOUND(40401, "资源不存在"),
    RESOURCE_STATUS_INVALID(40901, "资源状态不允许当前操作"),
    FAVORITE_DUPLICATE(40902, "重复收藏"),
    FILE_TOO_LARGE(41301, "文件过大"),
    FILE_TYPE_NOT_ALLOWED(41501, "文件类型不允许"),
    RATE_LIMITED(42901, "请求过于频繁"),

    /**
     * 5xxxx 表示服务端内部异常或外部依赖异常。
     */
    SERVER_ERROR(50001, "服务端异常");

    /**
     * 对外返回的稳定错误码，避免前端依赖 Java 枚举名称。
     */
    private final int code;
    /**
     * 默认错误提示，可在抛出 BusinessException 时按具体场景覆盖。
     */
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
