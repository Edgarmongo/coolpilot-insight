package com.coolpilot.insight.common.exception;

/**
 * 业务错误码。
 *
 * <p>编码规则见 R1-06：前三位对应 HTTP 类别，后两位为该类内的序号。
 * 这样既能被前端按区间归类，也能保留精确到场景的码值。
 */
public enum ErrorCode {

    /** 参数校验失败 */
    PARAM_INVALID(40001, 400, "参数不合法"),

    /** 未登录或 Token 缺失 */
    UNAUTHORIZED(40100, 401, "未登录"),
    /** 凭证错误（账号不存在或密码错误——故意不区分，避免账号枚举） */
    CREDENTIALS_INVALID(40101, 401, "账号或密码错误"),

    /** 已登录但无权限 */
    FORBIDDEN(40300, 403, "权限不足"),

    /** 资源不存在 */
    NOT_FOUND(40400, 404, "资源不存在"),

    /** 当前状态不允许该操作 */
    STATE_CONFLICT(40900, 409, "当前状态不允许该操作"),
    /** 唯一约束冲突 */
    DUPLICATE(40901, 409, "资源已存在"),

    /** 额度/频率超限 */
    QUOTA_EXCEEDED(42900, 429, "额度已用尽"),

    /** 关键配置缺失（如默认分析档案） */
    CONFIG_MISSING(50001, 500, "系统配置缺失"),

    /** 外部依赖失败（AI 服务、渲染服务等） */
    UPSTREAM_ERROR(50200, 502, "依赖服务不可用"),

    /** 兜底 */
    INTERNAL(50000, 500, "服务器内部错误");

    private final int code;
    private final int httpStatus;
    private final String defaultMessage;

    ErrorCode(int code, int httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public int getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
