package com.coolpilot.insight.integration.capability;

import java.util.List;

/**
 * 统一 Registry 快照（Python {@code GET /v1/registry/snapshot} 的 Java 侧表示）。
 *
 * <p>存在的理由（SOURCE-04-02 §A/§B）：计算 Effective 需要**能力清单**与
 * **Adapter 诊断**两类数据。若分两次请求两个端点，Registry 可能在两次之间变化，
 * 两端口径不一致就会拼出一个「从未同时存在过」的事实。
 *
 * <p>本对象表达**一次请求拿到的完整快照**，是一个不可变整体：
 *
 * <ul>
 *   <li>{@code snapshotId} 由 Python 侧按**内容**计算，不是时间戳；</li>
 *   <li>{@code generatedAt} 只用于观察与排障，<b>不得</b>用于判断一致性；</li>
 *   <li>使用本对象时不允许把两个不同 {@code snapshotId} 的数据合并。</li>
 * </ul>
 *
 * @param snapshotId      内容哈希标识，形如 {@code sha256:<64 hex>}
 * @param contractVersion 快照契约版本
 * @param schemaVersion   能力 Schema 版本（与 {@link CapabilityContractParser#SUPPORTED_SCHEMA_MAJOR} 对照）
 * @param generatedAt     生成时间，仅观察用
 * @param adapters        Adapter 注册状态（判 {@code ADAPTER_MISSING} 用）
 * @param capabilities    Observed 能力文档（语义等同 {@code /v1/capabilities} 的 connectors）
 * @param diagnostics     诊断记录（区分「未注册」与「函数解析失败」用）
 */
public record RegistrySnapshot(
        String snapshotId,
        String contractVersion,
        String schemaVersion,
        String generatedAt,
        List<AdapterView> adapters,
        List<CapabilityDocument> capabilities,
        List<Diagnostic> diagnostics) {

    /**
     * 一个 Adapter（接入实现）的注册状态。
     *
     * <p><b>必须完整映射 Python 的 {@code AdapterMetadata.to_v1()}</b>：
     * 丢弃任何字段都会让上层失去一个结构化判断依据，只能退化为靠
     * {@code sourceCode} 或能力数量推断——那是巧合，不是契约。
     *
     * @param connectorCode     接入实现标识
     * @param sourceCode        平台标识
     * @param connectorVersion  接入实现版本
     * @param loadStatus        {@code LOADED} / {@code FAILED}
     * @param module            Python 模块路径（诊断与定位用）
     * @param platformScoped    <b>关键</b>：是否为真实平台接入。
     *                          {@code false} 表示链路验证替身，04-03 必须排除它，
     *                          不得靠 {@code sourceCode == "STUB"} 或能力数量推断
     * @param capabilityCount   已兑现能力数
     * @param errorCode         加载失败时的错误码
     * @param errorMessage      加载失败时的说明
     * @param capabilities      该接入下的能力（Adapter 视角，与顶层平台文档互为冗余）
     */
    public record AdapterView(String connectorCode, String sourceCode, String connectorVersion,
                              String loadStatus, String module, Boolean platformScoped,
                              Integer capabilityCount, String errorCode, String errorMessage,
                              List<CapabilityItem> capabilities) {

        public boolean isLoaded() {
            return "LOADED".equals(loadStatus);
        }

        /** 是否真实平台接入。缺失该字段时按 {@code true} 处理（保守：不误排除）。 */
        public boolean isPlatformScoped() {
            return platformScoped == null || Boolean.TRUE.equals(platformScoped);
        }
    }

    /**
     * 一条诊断记录。**这是原因，不是状态。**
     *
     * @param connectorCode  接入实现
     * @param capabilityCode 能力码；为 {@code null} 表示整个 Adapter 级诊断
     * @param reason         原因码（如 {@code INVALID_IMPLEMENTATION}）
     * @param detail         说明
     * @param adapterFunction 相关函数引用
     */
    public record Diagnostic(String connectorCode, String capabilityCode, String reason,
                             String detail, String adapterFunction) {

        /** 函数引用解析不到 / 不可调用。 */
        public static final String REASON_INVALID_IMPLEMENTATION = "INVALID_IMPLEMENTATION";
        /** 整个 Adapter 加载失败。 */
        public static final String REASON_ADAPTER_NOT_LOADED = "NOT_LOADED";

        public boolean isFunctionProblem() {
            return REASON_INVALID_IMPLEMENTATION.equals(reason);
        }

        public boolean isAdapterProblem() {
            return REASON_ADAPTER_NOT_LOADED.equals(reason);
        }
    }

    // ------------------------------------------------------------------
    // 便捷查询
    // ------------------------------------------------------------------
    /** 按 connectorCode 找能力文档；找不到返回 {@code null}。 */
    public CapabilityDocument findConnector(String connectorCode) {
        if (connectorCode == null || capabilities == null) {
            return null;
        }
        for (CapabilityDocument doc : capabilities) {
            if (connectorCode.equals(doc.connectorCode())) {
                return doc;
            }
        }
        return null;
    }

    /** 按 connectorCode 找 Adapter 状态；找不到返回 {@code null}。 */
    public AdapterView findAdapter(String connectorCode) {
        if (connectorCode == null || adapters == null) {
            return null;
        }
        for (AdapterView view : adapters) {
            if (connectorCode.equals(view.connectorCode())) {
                return view;
            }
        }
        return null;
    }

    /**
     * 只保留**真实平台接入**，排除链路验证替身。
     *
     * <p>04-03 的核对必须用这个视图，而不是直接用 {@link #adapters()}——
     * 否则 STUB 之类的替身会混入 Desired/Observed 比对。
     */
    public List<AdapterView> platformScopedAdapters() {
        if (adapters == null) {
            return List.of();
        }
        return adapters.stream().filter(AdapterView::isPlatformScoped).toList();
    }

    /** 某接入下某能力的诊断；没有返回 {@code null}。 */
    public Diagnostic findDiagnostic(String connectorCode, String capabilityCode) {
        if (diagnostics == null) {
            return null;
        }
        for (Diagnostic d : diagnostics) {
            if (connectorCode != null && !connectorCode.equals(d.connectorCode())) {
                continue;
            }
            if (capabilityCode != null && !capabilityCode.equals(d.capabilityCode())) {
                continue;
            }
            if (capabilityCode == null && d.capabilityCode() != null) {
                continue;
            }
            return d;
        }
        return null;
    }

    /** 该能力是否被诊断标记为函数问题（决定 {@code FUNCTION_UNRESOLVED}）。 */
    public boolean hasFunctionProblem(String connectorCode, String capabilityCode) {
        Diagnostic d = findDiagnostic(connectorCode, capabilityCode);
        return d != null && d.isFunctionProblem();
    }
}
