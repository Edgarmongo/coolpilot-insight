package com.coolpilot.insight.integration.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        java.util.Set<String> seenDocuments = new java.util.HashSet<>();
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
                // implementationStatus 是**跨语言枚举契约**，必须严格校验：
                // 只读不验会让 Python 的拼写错误 / 版本不兼容值被 Java 当成正常状态
                // 继续核对，最终可能落到 AVAILABLE（SOURCE-04-03 独立复核 F1-2）。
                //
                // 不设默认值、不做映射：缺失 / null / 非法字符串一律拒绝整份快照，
                // 宁可"读不懂"也不要"猜成合法"。
                if (!CapabilityStatus.isKnown(item.implementationStatus())) {
                    String raw = item.implementationStatus() == null
                            ? "<缺失或 null>" : "\"" + item.implementationStatus() + "\"";
                    return Result.failure(Failure.BROKEN_CAPABILITY,
                            "能力 " + connectorLabel(doc) + "/" + item.code()
                                    + " 的 implementationStatus 非法: " + raw
                                    + "（合法值: " + KNOWN_STATUSES + "）");
                }
                if (!item.hasBoundFunction()) {
                    return Result.failure(Failure.BROKEN_CAPABILITY,
                            "能力 " + item.code() + " 缺少 adapterFunction");
                }
            }
            // 重复 connectorCode 必须拒绝：核对器 findConnector() 取**第一条**文档，
            // 而镜像校验按 connectorCode 建索引（后写覆盖）。两份取的不是同一条时，
            // 镜像校验与真实判定会看不同数据，等于给分叉留了暗门。
            if (seenDocuments.contains(doc.connectorCode())) {
                return Result.failure(Failure.BROKEN_CAPABILITY,
                        "capabilities 中存在重复的 connectorCode: " + doc.connectorCode());
            }
            seenDocuments.add(doc.connectorCode());
            documents.add(doc);
        }

        // 6.5) 两份 capabilities 副本必须互为镜像（O1 审计结论的落点）
        //
        // Python 的 snapshot() 用**同一次** evaluate_all() 同时生成 adapters[].capabilities
        // 与 capabilities[]（文档），对 platformScoped 接入二者是同一份声明。
        // 但 Java 同时消费两者：候选能力码取自 adapters，状态取自 documents。
        // 一旦二者分叉，就会出现"用 A 的清单 + B 的状态"这种无人定义过的组合。
        // 因此这里断言镜像关系，不满足即拒绝整份快照。
        String mirrorProblem = checkCapabilityMirror(adapters, documents);
        if (mirrorProblem != null) {
            return Result.failure(Failure.BROKEN_CAPABILITY, mirrorProblem);
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
    /** 合法实现状态清单（排序后拼串，保证同输入同报错文案）。 */
    private static final String KNOWN_STATUSES =
            String.join(" / ", CapabilityStatus.ALL.stream().sorted().toList());

    /**
     * 校验 adapters[].capabilities 与 capabilities[]（文档）互为镜像。
     *
     * <p>只比较 {@code platformScoped=true} 的接入：STUB 替身在文档侧本来就没有条目。
     *
     * <p>返回 {@code null} 表示一致，否则返回人类可读的差异说明。
     */
    private static String checkCapabilityMirror(List<RegistrySnapshot.AdapterView> adapters,
                                               List<CapabilityDocument> documents) {
        Map<String, CapabilityDocument> byConnector = new LinkedHashMap<>();
        for (CapabilityDocument d : documents) {
            if (d.connectorCode() != null) {
                byConnector.put(d.connectorCode(), d);
            }
        }
        for (RegistrySnapshot.AdapterView a : adapters) {
            if (!a.platformScoped()) {
                continue;
            }
            CapabilityDocument doc = byConnector.get(a.connectorCode());
            if (doc == null) {
                return "Adapter " + a.connectorCode()
                        + " 标记为真实平台接入，但 capabilities 中没有对应文档";
            }
            Map<String, CapabilityItem> fromAdapter = new LinkedHashMap<>();
            for (CapabilityItem c : a.capabilities()) {
                fromAdapter.put(c.code(), c);
            }
            Map<String, CapabilityItem> fromDoc = new LinkedHashMap<>();
            for (CapabilityItem c : doc.capabilities()) {
                fromDoc.put(c.code(), c);
            }
            for (Map.Entry<String, CapabilityItem> e : fromAdapter.entrySet()) {
                CapabilityItem docCap = fromDoc.get(e.getKey());
                if (docCap == null) {
                    return "能力 " + a.connectorCode() + "/" + e.getKey()
                            + " 出现在 adapters[].capabilities，但不在 capabilities[] 文档中";
                }
                String diff = describeMirrorDiff(e.getValue(), docCap);
                if (diff != null) {
                    return "能力 " + a.connectorCode() + "/" + e.getKey() + " " + diff;
                }
            }
            for (String code : fromDoc.keySet()) {
                if (!fromAdapter.containsKey(code)) {
                    return "能力 " + a.connectorCode() + "/" + code
                            + " 出现在 capabilities[] 文档，但不在 adapters[].capabilities 中";
                }
            }
        }
        return null;
    }

    /**
     * 比较两份副本中**参与 Effective 推导**的字段。
     *
     * <p>比较范围（M1 裁决）：
     * <ul>
     *   <li>{@code implementationStatus} —— 决定第 ⑮~⑳ 步；</li>
     *   <li>{@code accessModes} —— 决定第 ⑭ 步（按集合比较，顺序无语义）；</li>
     *   <li>{@code evidence} —— 决定第 ⑮~⑰ 步，比较
     *       {@code accepted} / {@code rejectionReason} / {@code contentHash} / {@code verifiedAt}。
     *       前两者直接决定原因码，后两者标识"是不是同一份证据记录"。</li>
     * </ul>
     *
     * <p><b>边界：{@code adapterFunction} 不参与比较</b>。它在解析期只用于
     * {@code hasBoundFunction()} 的存在性校验，不进入 Effective 推导；两份不同不会
     * 改变任何判定结果。若将来它参与判定，必须同步加入这里。
     *
     * @return {@code null} 表示一致；否则返回人类可读的差异说明
     */
    private static String describeMirrorDiff(CapabilityItem adapterCap, CapabilityItem docCap) {
        if (!java.util.Objects.equals(adapterCap.implementationStatus(),
                docCap.implementationStatus())) {
            return "的 implementationStatus 两份副本不一致: adapters="
                    + adapterCap.implementationStatus() + "，capabilities="
                    + docCap.implementationStatus();
        }
        List<String> adapterModes = normalizedModes(adapterCap.accessModes());
        List<String> docModes = normalizedModes(docCap.accessModes());
        if (!adapterModes.equals(docModes)) {
            return "的 accessModes 两份副本不一致: adapters=" + adapterModes
                    + "，capabilities=" + docModes;
        }
        String adapterEvidence = evidenceFingerprint(adapterCap.evidence());
        String docEvidence = evidenceFingerprint(docCap.evidence());
        if (!adapterEvidence.equals(docEvidence)) {
            return "的 evidence 两份副本不一致: adapters=" + adapterEvidence
                    + "，capabilities=" + docEvidence;
        }
        return null;
    }

    /** accessModes 是**集合**语义，排序后比较，避免顺序差异被当成不一致。 */
    private static List<String> normalizedModes(List<String> modes) {
        if (modes == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(modes);
        out.sort(String::compareTo);
        return out;
    }

    /** 证据指纹：只取影响判定与记录身份的四项。 */
    private static String evidenceFingerprint(CapabilityItem.Evidence ev) {
        if (ev == null) {
            return "<none>";
        }
        return "accepted=" + ev.accepted()
                + ",rejectionReason=" + ev.rejectionReason()
                + ",contentHash=" + ev.contentHash()
                + ",verifiedAt=" + ev.verifiedAt();
    }

    /** 诊断用标签：优先 connectorCode，退回 sourceCode。 */
    private static String connectorLabel(CapabilityDocument doc) {
        if (doc == null) {
            return "?";
        }
        if (doc.connectorCode() != null && !doc.connectorCode().isBlank()) {
            return doc.connectorCode();
        }
        return doc.sourceCode() == null ? "?" : doc.sourceCode();
    }

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
