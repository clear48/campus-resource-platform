package com.john.campus.exception;

import com.john.campus.common.ApiResponse;
import com.john.campus.common.ErrorCode;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * 全局异常处理器，保证成功和失败接口都返回 ApiResponse 结构。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常已经携带错误码，按错误码映射 HTTP 状态并返回业务提示。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        return ResponseEntity.status(resolveHttpStatus(ex.getCode()))
                .body(ApiResponse.fail(ex.getCode(), ex.getMessage()));
    }

    /**
     * 处理 @RequestBody 参数校验失败，优先返回第一个字段错误，便于前端定位。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValidException(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse(ErrorCode.PARAM_ERROR.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.PARAM_ERROR.getCode(), message));
    }

    /**
     * 处理表单或查询对象绑定失败，和 JSON 参数校验保持相同返回结构。
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Void>> handleBindException(BindException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse(ErrorCode.PARAM_ERROR.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.PARAM_ERROR.getCode(), message));
    }

    /**
     * 处理路径变量、查询参数上的单个约束校验异常。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolationException(ConstraintViolationException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.PARAM_ERROR.getCode(), ex.getMessage()));
    }

    /**
     * 处理查询参数类型转换失败，例如 Long 参数传入非数字字符串。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException ex) {
        // GET 查询参数类型错误常见于 parentId=abc，统一返回参数错误而不是兜底 500。
        String message = ex.getName() + " 参数格式错误";
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.PARAM_ERROR.getCode(), message));
    }

    /**
     * 处理 JSON 语法错误或请求体不可读场景。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadableException() {
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.PARAM_ERROR));
    }

    /**
     * 处理文件上传超过大小限制的异常（multipart 在进入 Controller 前抛出），统一映射为“文件过大”。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSizeExceededException() {
        return ResponseEntity.status(resolveHttpStatus(ErrorCode.FILE_TOO_LARGE.getCode()))
                .body(ApiResponse.fail(ErrorCode.FILE_TOO_LARGE));
    }

    /**
     * 处理 multipart 请求缺少文件部分的异常（如上传未携带 file），统一按参数错误返回。
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingServletRequestPartException(MissingServletRequestPartException ex) {
        String message = ex.getRequestPartName() + " 文件参数缺失";
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.PARAM_ERROR.getCode(), message));
    }

    /**
     * 处理必填查询参数缺失（如 MD5 预检缺少 fileMd5 或 fileSize），统一按参数错误返回。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingServletRequestParameterException(
            MissingServletRequestParameterException ex) {
        String message = ex.getParameterName() + " 参数缺失";
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.PARAM_ERROR.getCode(), message));
    }

    /**
     * 兜底异常不暴露堆栈细节，避免把服务端内部实现泄露给客户端。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException() {
        return ResponseEntity.internalServerError().body(ApiResponse.fail(ErrorCode.SERVER_ERROR));
    }

    /**
     * 将业务错误码映射到 HTTP 状态，便于调用方同时利用 HTTP 语义和业务 code。
     */
    private HttpStatus resolveHttpStatus(int code) {
        if (code == ErrorCode.UNAUTHORIZED.getCode() || code == ErrorCode.TOKEN_BLACKLISTED.getCode()) {
            return HttpStatus.UNAUTHORIZED;
        }
        if (code == ErrorCode.FORBIDDEN.getCode()) {
            return HttpStatus.FORBIDDEN;
        }
        if (code == ErrorCode.RESOURCE_NOT_FOUND.getCode()) {
            return HttpStatus.NOT_FOUND;
        }
        if (code == ErrorCode.RATE_LIMITED.getCode()) {
            return HttpStatus.TOO_MANY_REQUESTS;
        }
        if (code == ErrorCode.FILE_TOO_LARGE.getCode()) {
            return HttpStatus.PAYLOAD_TOO_LARGE;
        }
        if (code >= 50000) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return HttpStatus.BAD_REQUEST;
    }
}
