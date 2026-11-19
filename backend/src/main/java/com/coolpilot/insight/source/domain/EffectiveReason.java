package com.coolpilot.insight.source.domain;

import java.util.List;
import java.util.Set;

/**
 * Effective 的**精确原因**（ADR-008，Java 内部模型，20 值）。
 *
 * <p>为什么需要它：{@link EffectiveStatus} 只有八值，粒度不足以区分
 * "能力没注册"与"函数解析不到"，也无法表达"已注册但未验证"——
 * 后者的 {@code effectiveStatus} 是 {@code UNKNOWN}，与"服务不可达"撞车。
 * 没有原因码，下游只能靠猜。
 *
 * <p>治理规则（`SOURCE-04-04-EFFECTIVE-REASON.md` §6）：
 * <ul>
 *   <li>新增值必须同时补定义、判定条件、状态映射、正向测试；</li>
 *   <li>不得改变既有值的含义；</li>
 *   <li>一个值只对应一个 {@code effectiveStatus}（多对一，不可一对多）。</li>
 * </ul>
 *
 * <p>本枚举**不进入** Python 的输出，因此不触碰 SOURCE-02 Schema。
 */
public final class EffectiveReason {

    /** 无任何阻断。**只有** 与 {@code AVAILABLE} 搭配。 */
    public static final String NONE = "NONE";

    // ---- Source / 传输层 ----
    /** Java 侧已停用该 Source。 */
    public static final String SOURCE_DISABLED = "SOURCE_DISABLED";
    /** 采集服务不可达，且无历史快照。 */
    public static final String REGISTRY_UNREACHABLE = "REGISTRY_UNREACHABLE";
    /** 快照结构不可用（非法 JSON / 缺 schemaVersion / 主版本不支持 / 内部不一致）。 */
    public static final String SCHEMA_UNSUPPORTED = "SCHEMA_UNSUPPORTED";
    /** 只有过期快照（刷新失败且超 TTL）。 */
    public static final String OBSERVED_STALE = "OBSERVED_STALE";

    // ---- Desired ----
    /**
     * Java 侧 Desired 配置无法解析（非法 JSON / 未知主版本 / 结构或枚举非法 / 重复能力）。
     *
     * <p><b>不要</b>用 {@link #SCHEMA_UNSUPPORTED} 代替：那个码指的是 Python 快照结构，
     * 借用它会把排障方向指向采集服务，而真正该查的是 {@code ds_source.capability_json}
     * （SOURCE-04-03 评审 M1）。
     */
    public static final String DESIRED_UNPARSEABLE = "DESIRED_UNPARSEABLE";
    /** Desired 未声明该能力（或 desiredStatus=DISABLED）。 */
    public static final String NOT_DECLARED = "NOT_DECLARED";

    // ---- Connector 级 ----
    /** Adapter 加载失败。 */
    public static final String ADAPTER_NOT_LOADED = "ADAPTER_NOT_LOADED";
    /** Connector 版本与 Desired 锁定值不符。 */
    public static final String CONNECTOR_VERSION_MISMATCH = "CONNECTOR_VERSION_MISMATCH";
    /** 能力契约主版本不兼容。 */
    public static final String CONTRACT_VERSION_MISMATCH = "CONTRACT_VERSION_MISMATCH";

    // ---- 能力级（确定性不成立）----
    /** Registry 中不存在该能力，且无相关诊断。 */
    public static final String CAPABILITY_NOT_REGISTERED = "CAPABILITY_NOT_REGISTERED";
    /** 能力已声明，但实现函数无法解析或校验不通过。 */
    public static final String FUNCTION_UNRESOLVED = "FUNCTION_UNRESOLVED";
    /**
     * 引用指向的对象存在但不可调用。
     *
     * <p><b>当前不可达</b>：Python 侧只有单一诊断码 {@code INVALID_IMPLEMENTATION}，
     * 两种情况仅靠自由文本 detail 区分，而自由文本不得作为机器判据。
     * 短期一律归入 {@link #FUNCTION_UNRESOLVED}，本值保留待 Python 拆分子码后启用。
     */
    public static final String FUNCTION_NOT_CALLABLE = "FUNCTION_NOT_CALLABLE";
    /** 能力被 Python 显式禁用。 */
    public static final String CAPABILITY_DISABLED = "CAPABILITY_DISABLED";
    /** 平台客观不支持。 */
    public static final String PLATFORM_UNSUPPORTED = "PLATFORM_UNSUPPORTED";
    /** Observed 的 accessModes 不覆盖 Desired 要求的模式。 */
    public static final String ACCESS_MODE_UNSUPPORTED = "ACCESS_MODE_UNSUPPORTED";

    // ---- 验证证据 ----
    /** 已注册但从未获得有效验证证据。 */
    public static final String VERIFICATION_REQUIRED = "VERIFICATION_REQUIRED";
    /** 证据超过有效期。 */
    public static final String EVIDENCE_EXPIRED = "EVIDENCE_EXPIRED";
    /** 证据完整性/范围/版本校验失败。 */
    public static final String EVIDENCE_INVALID = "EVIDENCE_INVALID";

    // ---- 运行时条件 ----
    /** 需要 AUTHENTICATED 但无可用账号。 */
    public static final String ACCOUNT_REQUIRED = "ACCOUNT_REQUIRED";
    /** 可用但受限。 */
    public static final String DEGRADED = "DEGRADED";

    /** 全部 21 个取值。 */
    public static final List<String> ALL = List.of(
            NONE,
            SOURCE_DISABLED, REGISTRY_UNREACHABLE, SCHEMA_UNSUPPORTED, OBSERVED_STALE,
            DESIRED_UNPARSEABLE, NOT_DECLARED,
            ADAPTER_NOT_LOADED, CONNECTOR_VERSION_MISMATCH, CONTRACT_VERSION_MISMATCH,
            CAPABILITY_NOT_REGISTERED, FUNCTION_UNRESOLVED, FUNCTION_NOT_CALLABLE,
            CAPABILITY_DISABLED, PLATFORM_UNSUPPORTED, ACCESS_MODE_UNSUPPORTED,
            VERIFICATION_REQUIRED, EVIDENCE_EXPIRED, EVIDENCE_INVALID,
            ACCOUNT_REQUIRED, DEGRADED);

    /**
     * 当前**可达**的原因（20 个）——即可由真实输入触发的。
     *
     * <p>{@link #FUNCTION_NOT_CALLABLE} 不在其中，见其 javadoc。
     * 验收要求：这 20 个各有一条正向推导测试，
     * {@link #FUNCTION_NOT_CALLABLE} 有一条"不可达"测试。
     */
    public static final Set<String> REACHABLE = Set.copyOf(
            ALL.stream().filter(r -> !FUNCTION_NOT_CALLABLE.equals(r)).toList());

    private EffectiveReason() {
    }
}
