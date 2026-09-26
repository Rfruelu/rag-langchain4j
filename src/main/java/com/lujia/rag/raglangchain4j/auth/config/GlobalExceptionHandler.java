package com.lujia.rag.raglangchain4j.auth.config;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.lujia.rag.raglangchain4j.common.ratelimit.RateLimitExceededException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理（Sa-Token 认证异常）
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 未登录异常
     */
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<Map<String, Object>> handleNotLoginException(NotLoginException e) {
        log.warn("未登录访问: {}", e.getMessage());
        return build(401, "未登录或登录已过期");
    }

    /**
     * 无权限异常
     */
    @ExceptionHandler(NotPermissionException.class)
    public ResponseEntity<Map<String, Object>> handleNotPermissionException(NotPermissionException e) {
        log.warn("无权限访问: {}", e.getMessage());
        return build(403, "无访问权限");
    }

    /**
     * 无角色异常
     */
    @ExceptionHandler(NotRoleException.class)
    public ResponseEntity<Map<String, Object>> handleNotRoleException(NotRoleException e) {
        log.warn("无角色权限访问: {}", e.getMessage());
        return build(403, "无角色权限");
    }

    /**
     * 请求参数缺失或类型不匹配
     */
    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequestException(Exception e) {
        log.warn("请求参数错误: {}", e.getMessage());
        return build(400, "请求参数错误");
    }

    /**
     * 触发限流（HTTP 429）
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimitExceededException(RateLimitExceededException e) {
        log.warn("请求被限流: {}", e.getMessage());
        return build(429, e.getMessage());
    }

    /**
     * 业务异常（项目约定：业务错误以 RuntimeException 抛出，消息面向用户）
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(RuntimeException e) {
        log.warn("业务异常: {}", e.getMessage());
        return build(400, e.getMessage());
    }

    /**
     * 其他异常（不向前端泄露内部错误信息）
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        log.error("系统异常", e);
        return build(500, "系统异常，请稍后重试");
    }

    private ResponseEntity<Map<String, Object>> build(int code, String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("code", code);
        result.put("message", message);
        return ResponseEntity.status(code).body(result);
    }
}
