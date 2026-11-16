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
 * <p>不接受的六种情况（对应验收 A07）：
 *
 * <ol>
 *   <li>响应为空 / 非 JSON / 非 object；</li>
 *   <li>缺 {@code snapshotId}，或格式不是 {@code sha256:<64 hex>}；</li>
 *   <li>缺 {@code schemaVersion}，或主版本超出 {@link CapabilityContractParser#SUPPORTED_SCHEMA_MAJOR}；</li>
 *   <li>缺 {@code capabilities} / {@code diagnostics} / {@code adapters} 任一数组；</li>
 *   <li>某条能力文档无法解析（缺 {@code code} / {@code adapterFunction} 等必需字段）；</li>
 *   <li><b>内部不自洽</b>：诊断与能力清单互相矛盾——见 {@link #INVALID_IMPLEMENTATION}
 *       与 {@code EVIDENCE_*} 的相反要求。</li>
 * </ol>
 */
public final class RegistrySnapshotParser {

    /** 函数问题诊断：该能力**不应**出现在 Observed 清单里。 */
    private static final String INVALID_IMPLEMENTATION = "INVALID_IMPLEMENTATION";
    /** 证据类诊断前缀：该能力**应当**出现在 Observed 清单里（能力兑现了，只是证据无效）。 */
    private static final String EVIDENCE_PREFIX = "EVIDENCE_";

    private static final ObjectMapper JSON = new ObjectMapper();

    private RegistrySnapshotParser() {
    }

    /**
     * 解析结果。
     *
     * @param snapshot 成功时的快照；失败为 {@code null}
     * @param errors   失败原因（人类可读，**不用于机器判断**）
     */
    public record Result(RegistrySnapshot snapshot, List<String> errors) {

        public boolean ok() {
            return snapshot != null;
        }

        public static Result failure(String error) {
            return new Result(null, List.of(error));
        }
    }

    /**
     * 解析 {@code GET /v1/registry/snapshot} 的响应体。
     *
     * @param body 响应 JSON 原文（可以是含 {@code data} 信封的完整响应）
     */
    public static Result parse(String body) {
        if (body == null || body.isBlank()) {
            return Result.failure("快照响应为空");
        }
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (Exception e) {
            return Result.failure("快照响应无法解析为 JSON: " + e.getMessage());
        }
        if (root == null || !root.isObject()) {
            return Result.failure("快照响应结构非法，按 UNKNOWN 处理");
        }

        // 兼容统一信封 {code,msg,data}：优先取 data
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
            return Result.failure("快照缺少 snapshotId，无法确认一致性");
        }
        if (!isContentAddressedId(snapshotId)) {
            return Result.failure("snapshotId 格式非法（应为 sha256:<64 hex>）: " + snapshotId);
        }

        // 2) schemaVersion
        String schemaVersion = CapabilityContractParser.text(payload, "schemaVersion");
        int major = majorOf(schemaVersion);
        if (major <= 0) {
            return Result.failure("快照缺少 schemaVersion 或格式非法");
        }
        if (major > CapabilityContractParser.SUPPORTED_SCHEMA_MAJOR) {
            return Result.failure("不认识的 schemaVersion 主版本: " + schemaVersion
                    + "（支持 " + CapabilityContractParser.SUPPORTED_SCHEMA_MAJOR + ".x）");
        }

        // 3) 三个必需数组
        JsonNode capNodes = payload.get("capabilities");
        JsonNode diagNodes = payload.get("diagnostics");
        JsonNode adapterNodes = payload.get("adapters");
        if (capNodes == null || !capNodes.isArray()) {
            return Result.failure("快照缺少 capabilities 数组");
        }
        if (diagNodes == null || !diagNodes.isArray()) {
            return Result.failure("快照缺少 diagnostics 数组");
        }
        if (adapterNodes == null || !adapterNodes.isArray()) {
            return Result.failure("快照缺少 adapters 数组");
        }

        // 4) 解析 adapters
        List<RegistrySnapshot.AdapterView> adapters = new ArrayList<>();
        for (JsonNode n : adapterNodes) {
            adapters.add(new RegistrySnapshot.AdapterView(
                    CapabilityContractParser.text(n, "connectorCode"),
                    CapabilityContractParser.text(n, "sourceCode"),
                    CapabilityContractParser.text(n, "connectorVersion"),
                    CapabilityContractParser.text(n, "loadStatus"),
                    n.hasNonNull("capabilityCount") ? n.get("capabilityCount").asInt() : null));
        }

        // 5) 解析能力文档
        List<CapabilityDocument> documents = new ArrayList<>();
        for (JsonNode n : capNodes) {
            CapabilityDocument doc;
            try {
                doc = CapabilityContractParser.parseDocument(n);
            } catch (Exception e) {
                return Result.failure("能力文档解析失败: " + e.getMessage());
            }
            for (CapabilityItem item : doc.capabilities()) {
                if (item.code() == null || item.code().isBlank()) {
                    return Result.failure("快照中存在缺少 code 的能力条目");
                }
                if (!item.hasBoundFunction()) {
                    // Observed 清单里的能力必须绑定真实函数；没有函数说明 Python 侧输出异常
                    return Result.failure("能力 " + item.code() + " 缺少 adapterFunction");
                }
            }
            documents.add(doc);
        }

        // 6) 解析诊断
        List<RegistrySnapshot.Diagnostic> diagnostics = new ArrayList<>();
        for (JsonNode n : diagNodes) {
            diagnostics.add(new RegistrySnapshot.Diagnostic(
                    CapabilityContractParser.text(n, "connectorCode"),
                    CapabilityContractParser.text(n, "capabilityCode"),
                    CapabilityContractParser.text(n, "reason"),
                    CapabilityContractParser.text(n, "detail"),
                    CapabilityContractParser.text(n, "adapterFunction")));
        }

        // 7) 内部自洽性（关键）
        String inconsistency = checkConsistency(documents, diagnostics);
        if (inconsistency != null) {
            return Result.failure("快照内部不一致，拒绝接受: " + inconsistency);
        }

        return new Result(new RegistrySnapshot(
                snapshotId,
                CapabilityContractParser.text(payload, "contractVersion"),
                schemaVersion,
                CapabilityContractParser.text(payload, "generatedAt"),
                List.copyOf(adapters),
                List.copyOf(documents),
                List.copyOf(diagnostics)), List.of());
    }

    /**
     * 一致性校验。
     *
     * <p>两类诊断对能力清单有**相反**的要求，这是本校验的核心：
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
            // NOT_LOADED / 其它原因：不强制要求，跳过
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
