package com.coolpilot.insight.source.dto;

import com.coolpilot.insight.integration.capability.CapabilityItem;
import com.coolpilot.insight.integration.capability.RegistrySnapshot;
import com.coolpilot.insight.source.domain.DesiredCapability;
import com.coolpilot.insight.source.domain.EffectiveCapability;
import lombok.Data;

import java.util.List;

/**
 * 单个数据源的**三态明细**（SOURCE-04-06）。
 *
 * <p>与列表视图的区别：列表只给结论，这里给出"为什么是这个结论"的原料——
 * Desired 原样、Observed 实际加载内容与诊断、Effective 结论。
 *
 * <p>三块刻意**平行**放置，不合并成一行行对齐的表格：
 * 同一能力在 Desired 与 Observed 里可能根本不存在（未声明 / 未注册），
 * 强行 JOIN 成一行会让"缺失"和"值为空"无法区分。
 */
@Data
public class SourceCapabilityDetailView {

    private Long sourceId;
    private String sourceCode;
    private String sourceName;

    /** {@code ENABLED} / {@code DISABLED}；停用时所有能力判 {@code SOURCE_DISABLED}。 */
    private String sourceStatus;

    // ---------------- Desired ----------------
    /** 解析出的结构版本；v1 行返回 {@code "1"}。 */
    private String desiredSchemaVersion;
    private List<DesiredCapability> desired;

    /** Desired 读不懂时的原因；正常为 {@code null}（与列表视图同义）。 */
    private String desiredError;

    // ---------------- Observed ----------------
    /** 只含该平台的真实接入（{@code platformScoped=true}）；STUB 不在此列。 */
    private List<ObservedConnector> observed;

    /** {@code FRESH} / {@code STALE} / {@code UNAVAILABLE}。 */
    private String snapshotState;
    private String snapshotDetail;
    private String snapshotId;
    private String snapshotGeneratedAt;

    // ---------------- Effective ----------------
    private List<EffectiveCapability> effective;

    /**
     * 一个接入实现的运行时视图。
     *
     * <p>{@code diagnostics} 与 {@code capabilities} 同源同一快照，不得分两次请求拼装。
     */
    @Data
    public static class ObservedConnector {
        private String connectorCode;
        private String connectorVersion;
        private String loadStatus;
        private boolean platformScoped;
        private Integer capabilityCount;
        private String errorCode;
        private String errorMessage;
        private List<CapabilityItem> capabilities;
        private List<RegistrySnapshot.Diagnostic> diagnostics;
    }
}
