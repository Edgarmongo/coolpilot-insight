package com.coolpilot.insight.source.domain;

import java.util.List;

/**
 * Desired 能力声明（SOURCE-04-04 §1 冻结结构）。
 *
 * <p><b>只表达业务期望</b>，不含任何实现状态：
 * 没有 {@code implementationStatus}、没有 {@code adapterFunction}、没有 {@code evidence}。
 * 那些属于 Observed，写进这里会让三态塌成两态。
 *
 * <p>唯一标识是四元组 {@code (sourceCode, connectorCode, code, accessMode)}——
 * 同一能力在不同接入、不同访问模式下是两个可执行面。
 *
 * @param sourceCode    平台码，如 DOUYIN
 * @param connectorCode 接入实现码，如 DOUYIN_PUBLIC。**每能力独立声明**，支撑多 Connector
 * @param code          能力码，如 COMMENT
 * @param category      能力分类，如 COMMENT
 * @param accessModes    业务允许的访问模式
 * @param desiredStatus  {@link #ENABLED} / {@link #DISABLED}
 * @param adapterVersion **可选**的 Adapter 版本锁定（SOURCE-04-04 §4.4）。
 *                       声明即锁定，Python 实际版本必须**精确匹配**；
 *                       为 {@code null} 表示不锁，采用 Registry 实际版本
 */
public record DesiredCapability(
        String sourceCode,
        String connectorCode,
        String code,
        String category,
        List<String> accessModes,
        String desiredStatus,
        String adapterVersion) {

    public static final String ENABLED = "ENABLED";
    public static final String DISABLED = "DISABLED";

    /** 不锁 Adapter 版本的便捷构造（绝大多数场景）。 */
    public DesiredCapability(String sourceCode, String connectorCode, String code,
                             String category, List<String> accessModes, String desiredStatus) {
        this(sourceCode, connectorCode, code, category, accessModes, desiredStatus, null);
    }

    /** 业务是否声明提供该能力。{@code DISABLED} 等同于"未声明"。 */
    public boolean enabled() {
        return ENABLED.equals(desiredStatus);
    }

    /** 业务是否允许该访问模式。 */
    public boolean allowsAccessMode(String mode) {
        return accessModes != null && accessModes.contains(mode);
    }

    /** 是否有任何一项被允许的访问模式。 */
    public boolean hasAnyAccessMode() {
        return accessModes != null && !accessModes.isEmpty();
    }
}
