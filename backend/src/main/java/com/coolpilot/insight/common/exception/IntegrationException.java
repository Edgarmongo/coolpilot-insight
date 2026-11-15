package com.coolpilot.insight.common.exception;

/** 外部依赖（Python 采集/AI 服务、第三方 API）调用失败。统一映射为 502。 */
public class IntegrationException extends BizException {

    public IntegrationException(String message) {
        super(ErrorCode.UPSTREAM_ERROR, message);
    }

    public IntegrationException(String message, Throwable cause) {
        super(ErrorCode.UPSTREAM_ERROR, message);
        initCause(cause);
    }
}
