package com.coolpilot.insight.common.web;

import lombok.Data;

/**
 * 统一响应结构。
 *
 * <p>{@code traceId} 由 {@link TraceIdFilter} 从 MDC 取，调用方无需传参——
 * 这样既保证每个响应都带，又不用改动任何一处 {@code Result.ok(...)} 调用。
 */
@Data
public class Result<T> {
    private int code;
    private String message;
    private T data;
    private String traceId;

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.code = 200;
        r.message = "success";
        r.data = data;
        r.traceId = TraceIdFilter.current();
        return r;
    }

    public static <T> Result<T> fail(int code, String message) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = message;
        r.traceId = TraceIdFilter.current();
        return r;
    }
}
