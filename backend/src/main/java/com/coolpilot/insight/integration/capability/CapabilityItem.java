package com.coolpilot.insight.integration.capability;

import java.util.List;

/**
 * 一条能力声明（SOURCE-02 §02-03 的 Java 侧表示）。
 *
 * <p>关键约束：{@code adapterFunction} 缺失时，即使 {@code implementationStatus}
 * 写着 {@code VERIFIED}，也**不可执行**——能力由实现推导，不由状态标签推导。
 *
 * @param code                 能力码，如 COMMENT
 * @param category             能力分类，如 COMMENT
 * @param name                 显示名
 * @param accessModes          访问模式：PUBLIC / AUTHENTICATED
 * @param implementationStatus 实现状态，见 {@link CapabilityStatus}
 * @param contractVersion      该能力的入参/出参契约版本
 * @param adapterFunction      Python 侧真实可调用函数标识
 * @param verifiedAt           最近一次真实验证时间；为空表示未验证
 * @param limits               数量与分页上限
 * @param retryPolicy          重试策略
 * @param semantics            读写语义。本契约只支持只读能力，取值只能是 READ
 * @param evidence             验证证据的绑定信息与校验结论；无证据时为 null
 */
public record CapabilityItem(
        String code,
        String category,
        String name,
        List<String> accessModes,
        String implementationStatus,
        String contractVersion,
        String adapterFunction,
        String verifiedAt,
        Limits limits,
        RetryPolicy retryPolicy,
        String semantics,
        Evidence evidence) {

    /** 数量与分页上限。 */
    public record Limits(Integer maxComments, Integer maxPageSize, Boolean supportsPagination) {
    }

    /**
     * 验证证据的绑定信息与校验结论（SOURCE-02 v1.1）。
     *
     * @param accepted        证据是否通过了完整性 / 范围 / 有效期校验
     * @param verifiedAt      最近一次真实验证时间
     * @param method          证据产生方式（如 REAL_PLATFORM_RUN）
     * @param contentHash     记录本体哈希
     * @param scope           证据的适用范围
     * @param rejectionReason 证据被拒绝时的原因码；accepted=true 时为 null
     * @param rejectionDetail 证据被拒绝时的说明
     */
    public record Evidence(Boolean accepted, String verifiedAt, String method, String contentHash,
                           Scope scope, String rejectionReason, String rejectionDetail) {

        /**
         * 证据的适用范围。
         *
         * @param sourceCode        平台
         * @param connectorCode     接入实现
         * @param connectorVersion  接入版本
         * @param capabilityCode    能力码
         * @param accessModes       证据覆盖的访问模式
         * @param contractVersion   契约版本
         * @param adapterFunction   函数引用
         * @param implementationHash 声明 + 目标函数的联合指纹
         */
        public record Scope(String sourceCode, String connectorCode, String connectorVersion,
                            String capabilityCode, List<String> accessModes, String contractVersion,
                            String adapterFunction, String implementationHash) {
        }
    }

    /** 重试策略。{@code retryableErrors} 取值必须是七类 failure_type 之一。 */
    public record RetryPolicy(Integer maxAttempts, List<String> retryableErrors) {
    }

    /** 是否绑定了真实函数引用。 */
    public boolean hasBoundFunction() {
        return adapterFunction != null && !adapterFunction.isBlank();
    }

    /** 实现状态本身是否允许执行（不含函数绑定检查）。 */
    public boolean statusAllowsExecution() {
        return CapabilityStatus.allowsExecution(implementationStatus);
    }

    /**
     * 综合判断：状态允许执行 **且** 绑定了真实函数。
     *
     * <p>这是 SOURCE-02 §02-03 的硬规则在 Java 侧的落点。
     */
    public boolean isExecutable() {
        return statusAllowsExecution() && hasBoundFunction();
    }

    /** 是否经过真实平台验证。 */
    public boolean isVerified() {
        return CapabilityStatus.VERIFIED.equals(implementationStatus) && verifiedAt != null;
    }

    /**
     * 证据是否有效（存在且通过了校验）。
     *
     * <p>这是 SOURCE-04-02 的硬性要求在 Java 侧的落点：
     * 证据存在但 {@code accepted=false} 时，该能力**不得**被当成已验证。
     */
    public boolean hasAcceptedEvidence() {
        return evidence != null && Boolean.TRUE.equals(evidence.accepted());
    }

    /**
     * 是否可执行：状态允许 **且** 绑定真实函数 **且** 证据未被判无效。
     *
     * <p>无证据的能力（{@code evidence == null}）按"未验证"处理——
     * 没有验证记录就不该被当成可执行，这与 Python 侧"声明一律从 UNVERIFIED 起步"一致。
     */
    public boolean isExecutableWithEvidence() {
        return isExecutable() && hasAcceptedEvidence();
    }
}
