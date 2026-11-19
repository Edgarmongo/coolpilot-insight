package com.coolpilot.insight.common.exception;

/**
 * 业务异常。
 *
 * <p>取代原先用 {@code IllegalArgumentException} / {@code IllegalStateException} 表达业务错误的方式——
 * 那两种异常无法携带错误码，前端只能靠文案判断，也无法稳定区分"参数错"和"状态不允许"。
 *
 * <p>不继承 {@code RuntimeException} 之外的类型，是为了不被 Spring 的默认处理器吞掉；
 * 由 {@code GlobalExceptionHandler} 统一翻译成 HTTP 状态 + 响应体。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message == null || message.isBlank() ? errorCode.getDefaultMessage() : message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public int getCode() {
        return errorCode.getCode();
    }

    public int getHttpStatus() {
        return errorCode.getHttpStatus();
    }

    // ---------- 便捷构造 ----------

    public static BizException notFound(String what) {
        return new BizException(ErrorCode.NOT_FOUND, what + "不存在");
    }

    public static BizException forbidden(String message) {
        return new BizException(ErrorCode.FORBIDDEN, message);
    }

    public static BizException param(String message) {
        return new BizException(ErrorCode.PARAM_INVALID, message);
    }

    public static BizException conflict(String message) {
        return new BizException(ErrorCode.STATE_CONFLICT, message);
    }
}
