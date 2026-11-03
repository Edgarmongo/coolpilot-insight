package com.dy.comment.config;

import com.dy.comment.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 缺少必填请求参数：属于客户端错误，返回 400 而非 500。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<String> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("[参数缺失] {}", e.getMessage());
        return Result.fail(400, "缺少必填参数: " + e.getParameterName());
    }

    @ExceptionHandler(RuntimeException.class)
    public Result<String> handleRuntime(RuntimeException e) {
        log.warn("[业务异常] {}", e.getMessage());
        return Result.fail(400, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<String> handleException(Exception e) {
        // 记录完整堆栈，否则线上问题无法定位
        log.error("[系统异常]", e);
        return Result.fail(500, "服务器内部错误");
    }
}
