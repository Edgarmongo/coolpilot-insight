package com.coolpilot.insight.source.domain;

import java.util.Set;

/**
 * Effective 准入分类（SOURCE-02 §02-02 冻结八值）。
 *
 * <p><b>不得增删改</b>。这八个值是契约的一部分，Python 侧的
 * {@code SOURCE-02-capability-schema.json} 同样定义了它们。
 * 需要更细的原因时，用 {@link EffectiveReason}，不要往这里加值。
 *
 * <p><b>本类不是执行准入判据</b>——{@link #allowsExecution} 才是。
 * 一个 Effective 是否可执行，取决于 {@code (status, reason)} 组合，
 * 不是只看 status。
 */
public final class EffectiveStatus {

    /** 可用且可执行。**必须同时** {@code reason == NONE} 才算可执行。 */
    public static final String AVAILABLE = "AVAILABLE";
    /** Adapter 未加载。 */
    public static final String ADAPTER_MISSING = "ADAPTER_MISSING";
    /** 版本不匹配（Connector 或 Contract）。 */
    public static final String VERSION_MISMATCH = "VERSION_MISMATCH";
    /** 可用但受限，降级执行。 */
    public static final String DEGRADED = "DEGRADED";
    /** 需要认证账号但没有可用账号。 */
    public static final String ACCOUNT_REQUIRED = "ACCOUNT_REQUIRED";
    /** Java 侧已停用该 Source。 */
    public static final String SOURCE_DISABLED = "SOURCE_DISABLED";
    /** 未注册（未声明 / Registry 无此能力 / 函数不成立 / 被禁用等）。 */
    public static final String UNREGISTERED = "UNREGISTERED";
    /** 无法获得可靠判断，或已判定不满足条件但不属于上述确定性分类。 */
    public static final String UNKNOWN = "UNKNOWN";

    /** 全部合法取值。 */
    public static final Set<String> ALL = Set.of(
            AVAILABLE, ADAPTER_MISSING, VERSION_MISMATCH, DEGRADED,
            ACCOUNT_REQUIRED, SOURCE_DISABLED, UNREGISTERED, UNKNOWN);

    private EffectiveStatus() {
    }

    /**
     * 执行准入判据——<b>白名单</b>。
     *
     * <p>刻意写成"枚举允许的"，而不是"排除禁止的"。
     * 后者（如 {@code !UNREGISTERED.equals(status)}）会把 {@code UNKNOWN}
     * 以及将来新增的任何状态默认放行，是最危险的一类缺陷。
     *
     * @param status Effective 准入分类
     * @param reason 精确原因
     */
    public static boolean allowsExecution(String status, String reason) {
        if (AVAILABLE.equals(status)) {
            // 可用还必须无任何阻断原因
            return EffectiveReason.NONE.equals(reason);
        }
        // 降级执行：状态本身即代表"可用但受限"
        return DEGRADED.equals(status);
    }
}
