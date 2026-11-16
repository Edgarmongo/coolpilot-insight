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
        RetryPolicy retryPolicy) {

    /** 数量与分页上限。 */
    public record Limits(Integer maxComments, Integer maxPageSize, Boolean supportsPagination) {
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
}
