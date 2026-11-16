package com.coolpilot.insight.integration.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 统一 Registry 快照的**严格解析与校验**（SOURCE-04-02 §A）。
 *
 * <p>设计原则：**只接受完整、结构合法、内部自洽的快照。**
 * 任何一项不满足就整体拒绝，<b>不做局部接受</b>——
 * 局部接受等于自己制造了一个混合快照，正是本机制要消除的东西。
 *
 * <h3>错误必须结构化</h3>
 * <p>失败原因以 {@link Failure} 枚举表达，**调用方只读这个枚举做机器分派**；
 * {@link Error#message()} 只用于日志与人工诊断，<b>不得</b>用于判断。
 * 这样改措辞、改语言都不会影响分类。
 */
public final class RegistrySnapshotParser {

    /** 函数问题诊断：该能力**不应**出现在 Observed 清单里。 */
    private static final String INVALID_IMPLEMENTATION = "INVALID_IMPLEMENTATION";
    /** 证据类诊断前缀：该能力**应当**出现在 Observed 清单里（能力兑现了，只是证据无效）。 */
    private static final String EVIDENCE_PREFIX = "EVIDENCE_";

    /** Java 认识的最高快照契约主版本。**与 Schema 主版本独立。** */
    public static final int SUPPORTED_CONTRACT_MAJOR = 1;

    private static final ObjectMapper JSON = new ObjectMapper();

    private RegistrySnapshotParser() {
    }

    /**
     * 失败原因码。**这是机器判断依据。**
     *
     * <p>每个值对应一处明确的校验失败点，不允许多个点共用一个码，
     * 也不允许为了少加枚举而把两种情况混在一起。
     */
    public enum Failure {
        /** 响应为空 / 非 JSON / 非 object */
        MALFORMED_BODY,
        /** 缺少 snapshotId */
        MISSING_SNAPSHOT_ID,
        /** snapshotId 不是内容哈希（例如时间戳） */
        MALFORMED_SNAPSHOT_ID,
        /** 缺少 schemaVersion 或主版本超出支持范围 */
        UNSUPPORTED_SCHEMA,
        /** 缺少 contractVersion 或主版本超出支持范围 */
        UNSUPPORTED_CONTRACT,
        /** 缺少 capabilities / diagnostics / adapters 任一数组 */
        MISSING_ARRAY,
        /** 某个 Adapter 条目结构非法（缺 connectorCode，或 platformScoped 缺失/类型非布尔） */
        BROKEN_ADAPTER,
        /** 某条能力文档结构非法（缺 code / 缺 adapterFunction） */
        BROKEN_CAPABILITY,
        /** 能力清单与诊断互相矛盾 */
        INCONSISTENT
    }

    /**
     * 一条解析错误。
     *
     * @param failure 结构化原因码 —— **机器只读这个**
     * @param message 人类可读说明 —— 只进日志，不参与判断
     */
    public record Error(Failure failure, String message) {
    }

    /**
     * 解析结果。
     *
     * @param snapshot 成功时的快照；失败为 {@code null}
     * @param error    失败时的结构化错误；成功为 {@code null}
     */
    public record Result(RegistrySnapshot snapshot, Error error) {

        public boolean ok() {
            return snapshot != null && error == null;
        }

        public static Result failure(Failure failure, String message) {
            return new Result(null, new Error(failure, message));
        }
    }

    /**
     * 解析 {@code GET /v1/registry/snapshot} 的响应体。
     *
     * @param body 响应 JSON 原文（可以是含 {@code data} 信封的完整响应）
     */
    public static Result parse(String body) {
        if (body == null || body.isBlank()) {
            return Result.failure(Failure.MALFORMED_BODY, "快照响应为空");
        }
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (Exception e) {
            return Result.failure(Failure.MALFORMED_BODY,
                    "快照响应无法解析为 JSON: " + e.getMessage());
        }
        if (root == null || !root.isObject()) {
            return Result.failure(Failure.MALFORMED_BODY, "快照响应结构非法（非 JSON 对象）");
        }

        // 兼容统一信封 {code,msg,data}
        JsonNode payload = root;
        if (!root.has("snapshotId") && root.has("data") && root.get("data").isObject()) {
            JsonNode data = root.get("data");
            if (data.has("snapshotId")) {
                payload = data;
            }
        }

        // 1) snapshotId
        String snapshotId = CapabilityContractParser.text(payload, "snapshotId");
        if (snapshotId == null || snapshotId.isBlank()) {
            return Result.failure(Failure.MISSING_SNAPSHOT_ID,
                    "快照缺少 snapshotId，无法确认一致性");
        }
        if (!isContentAddressedId(snapshotId)) {
            return Result.failure(Failure.MALFORMED_SNAPSHOT_ID,
                    "snapshotId 格式非法（应为 sha256:<64 hex>）");
        }

        // 2) schemaVersion（独立校验）
        String schemaVersion = CapabilityContractParser.text(payload, "schemaVersion");
        int schemaMajor = majorOf(schemaVersion);
        if (schemaMajor <= 0) {
            return Result.failure(Failure.UNSUPPORTED_SCHEMA,
                    "快照缺少 schemaVersion 或格式非法");
        }
        if (schemaMajor > CapabilityContractParser.SUPPORTED_SCHEMA_MAJOR) {
            return Result.failure(Failure.UNSUPPORTED_SCHEMA,
                    "不认识的 schemaVersion 主版本: " + schemaVersion);
        }

        // 3) contractVersion（独立校验，**不得**与 schemaVersion 合并）
        String contractVersion = CapabilityContractParser.text(payload, "contractVersion");
        int contractMajor = majorOf(contractVersion);
        if (contractMajor <= 0) {
            return Result.failure(Failure.UNSUPPORTED_CONTRACT,
                    "快照缺少 contractVersion 或格式非法");
        }
        if (contractMajor > SUPPORTED_CONTRACT_MAJOR) {
            return Result.failure(Failure.UNSUPPORTED_CONTRACT,
                    "不认识的 contractVersion 主版本: " + contractVersion);
        }

        // 4) 三个必需数组
        JsonNode capNodes = payload.get("capabilities");
        JsonNode diagNodes = payload.get("diagnostics");
        JsonNode adapterNodes = payload.get("adapters");
        if (capNodes == null || !capNodes.isArray()) {
            return Result.failure(Failure.MISSING_ARRAY, "快照缺少 capabilities 数组");
        }
        if (diagNodes == null || !diagNodes.isArray()) {
            return Result.failure(Failure.MISSING_ARRAY, "快照缺少 diagnostics 数组");
        }
        if (adapterNodes == null || !adapterNodes.isArray()) {
            return Result.failure(Failure.MISSING_ARRAY, "快照缺少 adapters 数组");
        }

        // 5) adapters（完整字段映射）
        List<RegistrySnapshot.AdapterView> adapters = new ArrayList<>();
        for (JsonNode n : adapterNodes) {
            String connectorCode = CapabilityContractParser.text(n, "connectorCode");
            if (connectorCode == null || connectorCode.isBlank()) {
                return Result.failure(Failure.BROKEN_ADAPTER, "Adapter 条目缺少 connectorCode");
            }

            // platformScoped **必须存在且必须是布尔**。
            // 它是排除链路验证替身（STUB）的结构化依据；缺失时若默认 true，
            // 就是把未知数据当成真实平台接入 —— 失败开放（fail-open）。
            JsonNode psNode = n.get("platformScoped");
            if (psNode == null || psNode.isNull()) {
                return Result.failure(Failure.BROKEN_ADAPTER,
                        "Adapter " + connectorCode + " 缺少 platformScoped");
            }
            if (!psNode.isBoolean()) {
                return Result.failure(Failure.BROKEN_ADAPTER,
                        "Adapter " + connectorCode + " 的 platformScoped 不是布尔值: "
                                + psNode.getNodeType());
            }

            List<CapabilityItem> caps = new ArrayList<>();
            JsonNode inner = n.get("capabilities");
            if (inner != null && inner.isArray()) {
                for (JsonNode c : inner) {
                    caps.add(CapabilityContractParser.parseItem(c));
                }
            }
            adapters.add(new RegistrySnapshot.AdapterView(
                    connectorCode,
                    CapabilityContractParser.text(n, "sourceCode"),
                    CapabilityContractParser.text(n, "connectorVersion"),
                    CapabilityContractParser.text(n, "loadStatus"),
                    CapabilityContractParser.text(n, "module"),
                    psNode.asBoolean(),
                    n.hasNonNull("capabilityCount") ? n.get("capabilityCount").asInt() : null,
                    CapabilityContractParser.text(n, "errorCode"),
                    CapabilityContractParser.text(n, "errorMessage"),
                    List.copyOf(caps)));
        }

        // 6) 能力文档
        List<CapabilityDocument> documents = new ArrayList<>();
        for (JsonNode n : capNodes) {
            CapabilityDocument doc;
            try {
                doc = CapabilityContractParser.parseDocument(n);
            } catch (Exception e) {
                return Result.failure(Failure.BROKEN_CAPABILITY,
                        "能力文档解析失败: " + e.getMessage());
            }
            for (CapabilityItem item : doc.capabilities()) {
                if (item.code() == null || item.code().isBlank()) {
                    return Result.failure(Failure.BROKEN_CAPABILITY,
                            "快照中存在缺少 code 的能力条目");
                }
                if (!item.hasBoundFunction()) {
                    return Result.failure(Failure.BROKEN_CAPABILITY,
                            "能力 " + item.code() + " 缺少 adapterFunction");
                }
            }
            documents.add(doc);
        }

        // 7) 诊断
        List<RegistrySnapshot.Diagnostic> diagnostics = new ArrayList<>();
        for (JsonNode n : diagNodes) {
            diagnostics.add(new RegistrySnapshot.Diagnostic(
                    CapabilityContractParser.text(n, "connectorCode"),
                    CapabilityContractParser.text(n, "capabilityCode"),
                    CapabilityContractParser.text(n, "reason"),
                    CapabilityContractParser.text(n, "detail"),
                    CapabilityContractParser.text(n, "adapterFunction")));
        }

        // 8) 内部自洽性
        String inconsistency = checkConsistency(documents, diagnostics);
        if (inconsistency != null) {
            return Result.failure(Failure.INCONSISTENT,
                    "快照内部不一致: " + inconsistency);
        }

        return new Result(new RegistrySnapshot(
                snapshotId,
                contractVersion,
                schemaVersion,
                CapabilityContractParser.text(payload, "generatedAt"),
                List.copyOf(adapters),
                List.copyOf(documents),
                List.copyOf(diagnostics)), null);
    }

    /**
     * 一致性校验。
     *
     * <p>两类诊断对能力清单有**相反**的要求：
     *
     * <table border="1">
     *   <tr><th>诊断原因</th><th>该能力是否应出现在 Observed 清单</th></tr>
     *   <tr><td>{@code INVALID_IMPLEMENTATION}</td><td><b>不应</b>（函数解析不到，进不了清单）</td></tr>
     *   <tr><td>{@code EVIDENCE_*}（篡改/过期/版本不符…）</td><td><b>应当</b>（能力兑现了，只是证据无效）</td></tr>
     * </table>
     *
     * <p>若违反，说明 capabilities 与 diagnostics 不是同一次计算产生的——
     * 这正是跨请求混合数据的典型症状。
     */
    private static String checkConsistency(List<CapabilityDocument> documents,
                                           List<RegistrySnapshot.Diagnostic> diagnostics) {
        Set<String> observed = new HashSet<>();
        for (CapabilityDocument doc : documents) {
            for (CapabilityItem item : doc.capabilities()) {
                observed.add(key(doc.connectorCode(), item.code()));
            }
        }

        Set<String> reported = new LinkedHashSet<>();
        for (RegistrySnapshot.Diagnostic d : diagnostics) {
            String cap = d.capabilityCode();
            if (cap == null || cap.isBlank()) {
                continue;  // Adapter 级诊断不参与
            }
            String k = key(d.connectorCode(), cap);
            String reason = d.reason() == null ? "" : d.reason();

            if (INVALID_IMPLEMENTATION.equals(reason)) {
                if (observed.contains(k)) {
                    reported.add(k + " 被诊断为函数不可用，却同时出现在能力清单里");
                }
            } else if (reason.startsWith(EVIDENCE_PREFIX)) {
                if (!observed.contains(k)) {
                    reported.add(k + " 有证据类诊断，却不在能力清单里");
                }
            }
        }
        return reported.isEmpty() ? null : String.join("; ", reported);
    }

    private static String key(String connectorCode, String code) {
        return (connectorCode == null ? "" : connectorCode) + "/" + code;
    }

    /** {@code sha256:<64 hex>}。**不接受**时间戳或自增号。 */
    static boolean isContentAddressedId(String id) {
        if (id == null || !id.startsWith("sha256:")) {
            return false;
        }
        String hex = id.substring("sha256:".length());
        if (hex.length() != 64) {
            return false;
        }
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static int majorOf(String version) {
        if (version == null || version.isBlank()) {
            return 0;
        }
        int dot = version.indexOf('.');
        String head = dot > 0 ? version.substring(0, dot) : version;
        try {
            return Integer.parseInt(head.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
