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

    /**
     * `effectiveStatus` × `effectiveReason` 的**合法组合**（完整表，白名单）。
     *
     * <p>为什么需要这张表：`executable == allowsExecution(status, reason)` 只保证
     * "执行标志与准入计算一致"，不保证"状态与原因本身说得通"。缺了它，
     * `AVAILABLE + EVIDENCE_INVALID + executable=false` 这种自相矛盾的记录也能构造出来
     * （FIX-04 评审 L1）。
     *
     * <p>组合依据：`SOURCE-04-04-TASKCARD.md` §3.2 的 20 步判定表 +
     * `SOURCE-04-04-EFFECTIVE-REASON.md` §四 的判定顺序。**派生自契约，不是自创**——
     * 核对器能产出的每一组 (status, reason) 都在这张表里，反之表中每一组都有对应的
     * 推导步骤。
     *
     * <p>注意 `DEGRADED` 只允许配 `DEGRADED`：能力即使降级，其状态描述也必须是
     * "可用但受限"。"降级但证据无效"这类事实的正确表达是
     * `UNKNOWN + EVIDENCE_*`（证据闸门在降级之前）。
     */
    private static final java.util.Map<String, java.util.Set<String>> LEGAL_REASONS =
            java.util.Map.of(
                    AVAILABLE, java.util.Set.of(EffectiveReason.NONE),
                    SOURCE_DISABLED, java.util.Set.of(EffectiveReason.SOURCE_DISABLED),
                    ADAPTER_MISSING, java.util.Set.of(EffectiveReason.ADAPTER_NOT_LOADED),
                    VERSION_MISMATCH, java.util.Set.of(
                            EffectiveReason.CONNECTOR_VERSION_MISMATCH,
                            EffectiveReason.CONTRACT_VERSION_MISMATCH),
                    ACCOUNT_REQUIRED, java.util.Set.of(EffectiveReason.ACCOUNT_REQUIRED),
                    DEGRADED, java.util.Set.of(EffectiveReason.DEGRADED),
                    UNREGISTERED, java.util.Set.of(
                            EffectiveReason.NOT_DECLARED,
                            EffectiveReason.CAPABILITY_NOT_REGISTERED,
                            EffectiveReason.FUNCTION_UNRESOLVED,
                            EffectiveReason.FUNCTION_NOT_CALLABLE,
                            EffectiveReason.CAPABILITY_DISABLED,
                            EffectiveReason.PLATFORM_UNSUPPORTED,
                            EffectiveReason.ACCESS_MODE_UNSUPPORTED),
                    UNKNOWN, java.util.Set.of(
                            EffectiveReason.REGISTRY_UNREACHABLE,
                            EffectiveReason.SCHEMA_UNSUPPORTED,
                            EffectiveReason.OBSERVED_STALE,
                            EffectiveReason.DESIRED_UNPARSEABLE,
                            EffectiveReason.VERIFICATION_REQUIRED,
                            EffectiveReason.EVIDENCE_EXPIRED,
                            EffectiveReason.EVIDENCE_INVALID));

    private EffectiveStatus() {
    }

    /**
     * 该 {@code (status, reason)} 组合是否合法。
     *
     * <p>与 {@link #allowsExecution} 的分工：
     * <ul>
     *   <li>本方法回答"这两个字段放在一起说得通吗"——**构造期**校验；</li>
     *   <li>{@code allowsExecution} 回答"说得通的前提下能不能执行"——**准入**判据。</li>
     * </ul>
     * 两者都不能替对方把关。
     */
    public static boolean isLegalCombination(String status, String reason) {
        if (status == null || reason == null) {
            return false;
        }
        return LEGAL_REASONS.getOrDefault(status, java.util.Set.of()).contains(reason);
    }

    /** 某个 status 允许的 reason 集合（诊断用）。 */
    public static java.util.Set<String> legalReasonsOf(String status) {
        return LEGAL_REASONS.getOrDefault(status, java.util.Set.of());
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
        if (DEGRADED.equals(status)) {
            // 降级执行也要求 reason 匹配。
            //
            // 原实现 `return DEGRADED.equals(status)` 忽略了 reason，于是
            // DEGRADED + EVIDENCE_INVALID / EVIDENCE_EXPIRED / VERIFICATION_REQUIRED
            // 都会被判为可执行——"降级"成了"证据无效也能跑"的通行证
            // （SOURCE-04-03 独立复核 F1-3）。
            //
            // 证据矩阵（04-04 §3.2.1）规定：DEGRADED 必须持有已接受证据。
            // 证据不合格时推导层已把它变成 UNKNOWN，此处的 reason 校验是最后一道防线。
            return EffectiveReason.DEGRADED.equals(reason);
        }
        return false;
    }
}
