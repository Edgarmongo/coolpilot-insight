package com.coolpilot.insight.common.web;

import com.coolpilot.insight.common.exception.BizException;
import com.coolpilot.insight.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理。
 *
 * <p>统一把异常翻译成「HTTP 状态码 + 业务码 + 文案」三者一致：
 * HTTP 状态供网关/监控聚合，业务码供前端精确判断，文案给人看。
 *
 * <p>R2-04 之前所有异常都返回 HTTP 200（仅响应体带 code），
 * 导致监控看不出错误率、前端也拿不到 4xx 语义。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：HTTP 状态与业务码都取自 ErrorCode。 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<String>> handleBiz(BizException e) {
        log.warn("[业务异常] code={} {}", e.getCode(), e.getMessage());
        return ResponseEntity.status(e.getHttpStatus())
                .body(Result.fail(e.getCode(), e.getMessage()));
    }

    /** 缺少必填请求参数。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<String>> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("[参数缺失] {}", e.getMessage());
        return ResponseEntity.status(400)
                .body(Result.fail(ErrorCode.PARAM_INVALID.getCode(),
                        "缺少必填参数: " + e.getParameterName()));
    }

    /**
     * 越权访问兜底。
     *
     * <p>业务代码已基本改用 {@link BizException}，但拦截器与少量底层代码仍会抛 SecurityException。
     * 需区分"未登录"（401）与"已登录无权限"（403）——原实现一律返回 403，
     * 会让前端在未登录时走"权限不足"分支而不是跳登录页。
     */
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Result<String>> handleSecurity(SecurityException e) {
        String msg = e.getMessage() == null ? "权限不足" : e.getMessage();
        boolean unauthenticated = msg.contains("未登录");
        int status = unauthenticated ? 401 : 403;
        int code = unauthenticated ? ErrorCode.UNAUTHORIZED.getCode() : ErrorCode.FORBIDDEN.getCode();
        log.warn("[{}] {}", unauthenticated ? "未认证" : "越权访问", msg);
        return ResponseEntity.status(status).body(Result.fail(code, msg));
    }

    /** 遗留的 IllegalArgumentException：逐步被 BizException 取代，保留兜底。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<String>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("[参数非法] {}", e.getMessage());
        return ResponseEntity.status(400)
                .body(Result.fail(ErrorCode.PARAM_INVALID.getCode(), e.getMessage()));
    }

    /** 遗留的 IllegalStateException：逐步被 BizException 取代，保留兜底。 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Result<String>> handleIllegalState(IllegalStateException e) {
        log.warn("[状态冲突] {}", e.getMessage());
        return ResponseEntity.status(409)
                .body(Result.fail(ErrorCode.STATE_CONFLICT.getCode(), e.getMessage()));
    }

    /**
     * 路径不存在。
     *
     * <p>Spring Boot 4 用 {@code NoResourceFoundException} 表达静态资源/路径未命中；
     * 若不单独处理会被下面的兜底吞成 500，监控上表现为"内部错误"，掩盖了真实的 404。
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Result<String>> handleNotFound(
            org.springframework.web.servlet.resource.NoResourceFoundException e) {
        log.warn("[路径不存在] {}", e.getMessage());
        return ResponseEntity.status(404)
                .body(Result.fail(ErrorCode.NOT_FOUND.getCode(), "接口不存在"));
    }

    /**
     * 参数校验失败（@Valid）。
     *
     * <p>只返回第一条字段错误，避免把整个对象结构刷给前端；
     * 错误码统一 40001，message 带字段中文名。
     */
    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    public ResponseEntity<Result<String>> handleValidation(
            org.springframework.web.bind.MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getDefaultMessage())
                .orElse("参数校验失败");
        log.warn("[参数校验失败] {}", msg);
        return ResponseEntity.status(400)
                .body(Result.fail(ErrorCode.PARAM_INVALID.getCode(), msg));
    }

    /** 方法参数（非 @RequestBody）上的约束校验失败。 */
    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    public ResponseEntity<Result<String>> handleConstraint(
            jakarta.validation.ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .findFirst()
                .map(v -> v.getMessage())
                .orElse("参数校验失败");
        log.warn("[参数校验失败] {}", msg);
        return ResponseEntity.status(400)
                .body(Result.fail(ErrorCode.PARAM_INVALID.getCode(), msg));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<String>> handleException(Exception e) {
        // 记录完整堆栈，否则线上问题无法定位；对外只给通用文案，不泄露内部细节
        log.error("[系统异常]", e);
        return ResponseEntity.status(500)
                .body(Result.fail(ErrorCode.INTERNAL.getCode(), "服务器内部错误"));
    }
}
