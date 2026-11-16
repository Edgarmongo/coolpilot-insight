package com.coolpilot.insight.source.domain;

/**
 * 一条能力的 Effective 结论。
 *
 * <p>这是**派生结果**，不是持久化事实：由 Desired × Observed × Source 状态 × 账号
 * 实时计算得出（ADR-006）。不得写回数据库，也不得作为第二份权威数据缓存。
 *
 * <p>{@code effectiveStatus} 与 {@code effectiveReason} 必须**成对使用**：
 * 前者是准入分类（八值），后者是精确原因（二十值）。
 * 只看 status 会误导——`UNREGISTERED` 可能是"没注册"也可能是"函数解析不到"；
 * `UNKNOWN` 可能是"服务挂了"也可能是"已注册但没验证"。
 *
 * @param code            能力码
 * @param category        能力分类
 * @param effectiveStatus 准入分类（{@link EffectiveStatus} 八值之一）
 * @param effectiveReason 精确原因（{@link EffectiveReason} 二十值之一）
 * @param executable      是否允许进入执行流程。**由 status+reason 派生**，不可外部乱传
 * @param detail          人类可读说明
 */
public record EffectiveCapability(
        String code,
        String category,
        String effectiveStatus,
        String effectiveReason,
        boolean executable,
        String detail) {

    /**
     * 强制不变量：{@code executable} 必须等于白名单判据的结果。
     *
     * <p>这样"UNKNOWN 被标记为可执行"这类错误在**构造对象时**就不可能存在，
     * 不依赖调用方自觉。
     */
    public EffectiveCapability {
        boolean allowed = EffectiveStatus.allowsExecution(effectiveStatus, effectiveReason);
        if (executable != allowed) {
            throw new IllegalArgumentException(
                    "executable 与 (status, reason) 不一致: "
                            + effectiveStatus + "/" + effectiveReason
                            + " 期望 executable=" + allowed + "，实际=" + executable);
        }
        if (!EffectiveStatus.ALL.contains(effectiveStatus)) {
            throw new IllegalArgumentException("非法的 effectiveStatus: " + effectiveStatus);
        }
        if (!EffectiveReason.ALL.contains(effectiveReason)) {
            throw new IllegalArgumentException("非法的 effectiveReason: " + effectiveReason);
        }
    }

    /** 便捷构造：由 status+reason 自动派生 executable。 */
    public static EffectiveCapability of(String code, String category,
                                         String status, String reason, String detail) {
        return new EffectiveCapability(code, category, status, reason,
                EffectiveStatus.allowsExecution(status, reason), detail);
    }
}
