package com.coolpilot.insight.source.dto;

import com.coolpilot.insight.source.domain.DesiredCapability;
import com.coolpilot.insight.source.domain.EffectiveCapability;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 数据源视图：**三态并列**。
 *
 * <p>同一条能力会同时出现三个视角，刻意不合并——合并就等于让"配置说支持"
 * 冒充"运行时确实可用"（ADR-006 明令禁止）：
 *
 * <ul>
 *   <li>{@link #capability}：旧结构原样返回，兼容期供老前端读取；</li>
 *   <li>{@link #desired}：业务声明（来自 {@code capability_json}）；</li>
 *   <li>{@link #effective}：核对结论（按需实时计算，不落库）。</li>
 * </ul>
 *
 * <p>{@link #snapshotState} 是 Observed 的新鲜度。**只有 {@code FRESH} 时
 * {@code effective} 才反映真实运行时**；{@code STALE}/{@code UNAVAILABLE}
 * 时全部能力为 {@code UNKNOWN}，前端不得把这种状态当成"能力正常"。
 */
@Data
public class DataSourceView {

    private Long id;
    private String code;
    private String name;
    private String sourceType;
    private String adapterCode;
    private String status;

    /** 旧结构（v1 capability_json 原样），兼容期保留。 */
    private Map<String, Object> capability;

    /** Desired：业务声明。 */
    private List<DesiredCapability> desired;

    /** Effective：核对结论。 */
    private List<EffectiveCapability> effective;

    /** Observed 快照新鲜度：FRESH / STALE / UNAVAILABLE。 */
    private String snapshotState;

    /** 快照状态说明（人类可读）。 */
    private String snapshotDetail;

    private String remark;
}
