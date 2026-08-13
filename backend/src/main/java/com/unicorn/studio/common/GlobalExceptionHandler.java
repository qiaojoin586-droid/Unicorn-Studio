package com.unicorn.studio.common;

import com.unicorn.studio.model.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * 全局异常处理：所有错误响应统一 ApiResponse{code, message, data} 信封。
 * 未预期异常打印完整堆栈到日志（配合错误日志文件，出问题可快速定位），
 * 对外只返回友好提示，不泄漏内部细节。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 参数校验失败（@Valid）→ 400 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .findFirst()
                .orElse("参数校验失败");
        log.warn("参数校验失败: {} {}", e.getParameter().getParameterType().getSimpleName(), message);
        return ResponseEntity.badRequest().body(ApiResponse.error(400, message));
    }

    /** 请求体不可读（JSON 格式错误）→ 400 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.error(400, "请求体格式错误"));
    }

    /** 带状态码的业务异常（如作品不存在 404）→ 原状态码 + 统一信封 */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatus(ResponseStatusException e) {
        int status = e.getStatusCode().value();
        String message = e.getReason() != null ? e.getReason() : "请求失败";
        log.warn("业务异常: status={}, message={}", status, message);
        return ResponseEntity.status(e.getStatusCode()).body(ApiResponse.error(status, message));
    }

    /** 兜底：未知异常 → 500，完整堆栈只进日志 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(500, "服务器内部错误"));
    }
}
