package com.coolpilot.insight.integration.capability;

import java.util.Set;

/**
 * 能力实现状态（SOURCE-02 §02-03 的枚举，与 JSON Schema 逐字一致）。
 *
 * <p>注意：这里**没有** {@code NOT_LOADED} / {@code INVALID_IMPLEMENTATION}——
 * 那两个是诊断原因，不是状态。不要为了凑场景说明表而扩充枚举。
 */
public final class CapabilityStatus {

    /** 代码存在但从未验证 —— 不可执行。 */
    public static final String UNVERIFIED = "UNVERIFIED";
    /** 已实现且本地测试通过 —— 允许但需标注。 */
    public static final String IMPLEMENTED = "IMPLEMENTED";
    /** 已通过真实平台验证 —— 可执行。 */
    public static final String VERIFIED = "VERIFIED";
    /** 可用但受限 —— 降级执行。 */
    public static final String DEGRADED = "DEGRADED";
    /** 被显式禁用 —— 不可执行。 */
    public static final String DISABLED = "DISABLED";
    /** 平台不支持 —— 不可执行。 */
    public static final String UNSUPPORTED = "UNSUPPORTED";

    /** 全部合法取值。 */
    public static final Set<String> ALL = Set.of(
            UNVERIFIED, IMPLEMENTED, VERIFIED, DEGRADED, DISABLED, UNSUPPORTED);

    /** 允许执行的状态集合。 */
    private static final Set<String> EXECUTABLE = Set.of(VERIFIED, IMPLEMENTED, DEGRADED);

    private CapabilityStatus() {
    }

    /** 该实现状态是否允许执行。未知状态一律按不可执行处理。 */
    public static boolean allowsExecution(String status) {
        return status != null && EXECUTABLE.contains(status);
    }

    public static boolean isKnown(String status) {
        return status != null && ALL.contains(status);
    }
}
