package com.coolpilot.insight.integration.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Python 采集服务能力契约的**消费端解析器**（SOURCE-03 / SOURCE-02 §02-03、§02-05）。
 *
 * <p>职责很窄：**只解析，不裁决**。Effective 能力的推导属于 SOURCE-08，
 * 本类只保证 Java 侧能准确读懂 Python 的 Observed 输出，并且：
 *
 * <ul>
 *   <li>不认识 {@code schemaVersion} 主版本时**拒绝解析并标记未知**，
 *       绝不静默忽略、也绝不伪造为"不可用"；</li>
 *   <li>保留 {@code adapterFunction}，让上层能执行"函数缺失 ⇒ 不可执行"的硬规则；</li>
 *   <li>保留旧结构 {@code items} 的解析能力（兼容期）。</li>
 * </ul>
 */
public final class CapabilityContractParser {

    /** Java 认识的最高 Schema 主版本。 */
    public static final int SUPPORTED_SCHEMA_MAJOR = 1;

    private static final ObjectMapper JSON = new ObjectMapper();

    private CapabilityContractParser() {
    }

    /** 旧端点的能力条目（兼容期）。 */
    public record LegacyCapability(String sourceCode, boolean comments, boolean keywordSearch,
                                   String status, String note) {
    }

    /**
     * 解析 {@code GET /v1/capabilities} 的响应体。
     *
     * @param body 响应 JSON 原文
     * @return Observed 视图；主版本不支持时 {@code schemaSupported=false} 且文档为空
     */
    public static ObservedCapabilities parseCapabilities(String body) {
        if (body == null || body.isBlank()) {
            return new ObservedCapabilities("0", 0, false, List.of(),
                    List.of("能力响应为空，按 UNKNOWN 处理"));
        }
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (Exception e) {
            return new ObservedCapabilities("0", 0, false, List.of(),
                    List.of("能力响应无法解析: " + e.getMessage()));
        }
        if (root == null || !root.isObject()) {
            return new ObservedCapabilities("0", 0, false, List.of(),
                    List.of("能力响应结构非法，按 UNKNOWN 处理"));
        }

        String schemaVersion = text(root, "schemaVersion");
        JsonNode connectors = root.get("connectors");
        if ((connectors == null || !connectors.isArray())) {
            JsonNode data = root.get("data");
            if (data != null && data.isObject()) {
                schemaVersion = schemaVersion != null ? schemaVersion : text(data, "schemaVersion");
                connectors = data.get("connectors");
            }
        }
        if (schemaVersion == null && connectors != null && connectors.isArray()
                && connectors.size() > 0) {
            schemaVersion = text(connectors.get(0), "schemaVersion");
        }

        int major = majorOf(schemaVersion);
        List<String> warnings = new ArrayList<>();
        if (major <= 0) {
            warnings.add("缺少 schemaVersion，按 UNKNOWN 处理");
            return new ObservedCapabilities(schemaVersion, 0, false, List.of(), warnings);
        }
        if (major > SUPPORTED_SCHEMA_MAJOR) {
            warnings.add("不认识的 schemaVersion 主版本: " + schemaVersion + "，拒绝解析并标记 UNKNOWN");
            return new ObservedCapabilities(schemaVersion, major, false, List.of(), warnings);
        }

        List<CapabilityDocument> documents = new ArrayList<>();
        if (connectors != null && connectors.isArray()) {
            for (JsonNode node : connectors) {
                documents.add(parseDocument(node));
            }
        }
        return new ObservedCapabilities(schemaVersion, major, true, documents, warnings);
    }

    /** 解析旧结构的 {@code items}（兼容期）。 */
    public static List<LegacyCapability> parseLegacyItems(String body) {
        List<LegacyCapability> out = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return out;
        }
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode items = root.get("items");
            if (items == null || !items.isArray()) {
                return out;
            }
            for (JsonNode item : items) {
                out.add(new LegacyCapability(
                        text(item, "sourceCode"),
                        item.path("comments").asBoolean(false),
                        item.path("keywordSearch").asBoolean(false),
                        text(item, "status"),
                        text(item, "note")));
            }
        } catch (Exception ignored) {
            // 旧结构解析失败不应影响主流程
        }
        return out;
    }

    private static CapabilityDocument parseDocument(JsonNode node) {
        String schemaVersion = text(node, "schemaVersion");
        String sourceCode = text(node, "sourceCode");
        String connectorCode = text(node, "connectorCode");
        String connectorVersion = text(node, "connectorVersion");

        List<CapabilityItem> capabilities = new ArrayList<>();
        JsonNode caps = node.get("capabilities");
        if (caps != null && caps.isArray()) {
            for (JsonNode cap : caps) {
                capabilities.add(parseItem(cap));
            }
        }
        return new CapabilityDocument(schemaVersion, sourceCode, connectorCode, connectorVersion, capabilities);
    }

    private static CapabilityItem parseItem(JsonNode cap) {
        List<String> accessModes = new ArrayList<>();
        JsonNode modes = cap.get("accessModes");
        if (modes != null && modes.isArray()) {
            for (JsonNode m : modes) {
                accessModes.add(m.asText());
            }
        }

        CapabilityItem.Limits limits = null;
        JsonNode lim = cap.get("limits");
        if (lim != null && lim.isObject()) {
            limits = new CapabilityItem.Limits(
                    lim.has("maxComments") ? lim.get("maxComments").asInt() : null,
                    lim.has("maxPageSize") ? lim.get("maxPageSize").asInt() : null,
                    lim.has("supportsPagination") ? lim.get("supportsPagination").asBoolean() : null);
        }

        CapabilityItem.RetryPolicy policy = null;
        JsonNode rp = cap.get("retryPolicy");
        if (rp != null && rp.isObject()) {
            List<String> retryable = new ArrayList<>();
            JsonNode re = rp.get("retryableErrors");
            if (re != null && re.isArray()) {
                for (JsonNode r : re) {
                    retryable.add(r.asText());
                }
            }
            policy = new CapabilityItem.RetryPolicy(
                    rp.has("maxAttempts") ? rp.get("maxAttempts").asInt() : null, retryable);
        }

        return new CapabilityItem(
                text(cap, "code"),
                text(cap, "category"),
                text(cap, "name"),
                accessModes,
                text(cap, "implementationStatus"),
                text(cap, "contractVersion"),
                text(cap, "adapterFunction"),
                text(cap, "verifiedAt"),
                limits,
                policy);
    }

    /** 把 JSON 对象转成 Map，便于测试与调试。 */
    public static Map<String, Object> toMap(JsonNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return out;
        }
        node.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static int majorOf(String schemaVersion) {
        if (schemaVersion == null || schemaVersion.isBlank()) {
            return 0;
        }
        int dot = schemaVersion.indexOf('.');
        String head = dot > 0 ? schemaVersion.substring(0, dot) : schemaVersion;
        try {
            return Integer.parseInt(head.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
