package com.unicorn.studio.common;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 业务异常（含错误码），由 GlobalExceptionHandler 统一转 ApiResponse 信封。
 * 约定：400 参数/业务拒绝、403 无权限、404 不存在、409 冲突、429 限流。
 */
public class BusinessException extends ResponseStatusException {

    public BusinessException(HttpStatus status, String message) {
        super(status, message);
    }

    public static BusinessException badRequest(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, message);
    }

    public static BusinessException notFound(String message) {
        return new BusinessException(HttpStatus.NOT_FOUND, message);
    }

    public static BusinessException conflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, message);
    }

    public static BusinessException tooManyRequests(String message) {
        return new BusinessException(HttpStatus.TOO_MANY_REQUESTS, message);
    }
}
