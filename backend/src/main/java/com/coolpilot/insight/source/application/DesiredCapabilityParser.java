package com.coolpilot.insight.source.application;

import com.coolpilot.insight.source.domain.DesiredCapability;
import com.coolpilot.insight.source.domain.DesiredCapabilitySet;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code ds_source.capability_json} 的解析器，**同时兼容 v1 与 v2**。
 *
 * <p>为什么必须双读：v2 结构已在 SOURCE-04-04 冻结，但库中数据仍是 v1
 * （迁移独立立项，属 04-06）。核对层不能等迁移——否则 04-03 无法落地。
 *
 * <p>判定规则（SOURCE-04-04 §2.7）：
 * <table border="1">
 *   <tr><th>输入</th><th>行为</th></tr>
 *   <tr><td>无 {@code schemaVersion}</td><td>按 v1 解析，用 §2.2 确定性映射转成 v2 形状</td></tr>
 *   <tr><td>{@code schemaVersion = 2.x}</td><td>直接解析</td></tr>
 *   <tr><td>主版本 ≥ 3</td><td>拒绝（{@code supported=false}）</td></tr>
 * </table>
 *
 * <p>任何失败都**不抛异常**，而是返回 {@code supported=false}——
 * 核对层需要把"Desired 读不懂"表达成一个 Effective 结论。
 */
public final class DesiredCapabilityParser {

    private static final Logger log = LoggerFactory.getLogger(DesiredCapabilityParser.class);

    /** Java 认识的最高 Desired 主版本。 */
    public static final int SUPPORTED_MAJOR = 2;

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * v1 字段 → v2 能力码的**确定性映射**（SOURCE-04-04 §2.2）。
     *
     * <p>{@code accessModes} 取**保守默认**（§2.5）：取窄不取宽。
     * 宁可核对时判 `ACCESS_MODE_UNSUPPORTED`，也不要误判"可用"。
     */
    private record V1Mapping(String field, String code, String category, List<String> accessModes) {
    }

    private static final List<V1Mapping> V1_MAPPINGS = List.of(
            new V1Mapping("comments", "COMMENT", "COMMENT",
                    List.of("PUBLIC", "AUTHENTICATED")),
            new V1Mapping("contentDetail", "CONTENT_DETAIL", "CONTENT",
                    List.of("PUBLIC")),
            new V1Mapping("keywordSearch", "KEYWORD_SEARCH", "SEARCH",
                    List.of("PUBLIC")));

    /**
     * sourceCode → connectorCode（SOURCE-04-04 §2.3）。
     *
     * <p>**不用** {@code adapter_code} 直接拼接：它是小写平台级标识（{@code douyin}），
     * 而 connectorCode 是大写接入级（{@code DOUYIN_PUBLIC}），两者不是同一个概念。
     * 拼错会让核对永远判 `ADAPTER_MISSING`，且看起来像"Python 没加载"，排查成本极高。
     */
    private static final Map<String, String> CONNECTOR_BY_SOURCE = Map.of(
            "DOUYIN", "DOUYIN_PUBLIC",
            "XIAOHONGSHU", "XIAOHONGSHU_PUBLIC",
            "KUAISHOU", "KUAISHOU_PUBLIC",
            "WEB", "WEB_PUBLIC");

    private DesiredCapabilityParser() {
    }

    /**
     * 解析一个 Source 的 Desired。
     *
     * @param json        {@code capability_json} 原文，可为 null/空
     * @param sourceCode  平台码（{@code ds_source.code}），v1 映射需要
     */
    public static DesiredCapabilitySet parse(String json, String sourceCode) {
        if (json == null || json.isBlank()) {
            return DesiredCapabilitySet.empty();
        }
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (Exception e) {
            log.warn("[capability] capability_json 非法（source={}）: {}", sourceCode, e.getMessage());
            return DesiredCapabilitySet.unsupported("capability_json 非法 JSON: " + e.getMessage());
        }
        if (root == null || !root.isObject()) {
            return DesiredCapabilitySet.unsupported("capability_json 不是 JSON 对象");
        }

        String schemaVersion = text(root, "schemaVersion");
        if (schemaVersion == null || schemaVersion.isBlank()) {
            return parseV1(root, sourceCode);
        }

        int major = majorOf(schemaVersion);
        if (major <= 0) {
            return DesiredCapabilitySet.unsupported("schemaVersion 格式非法: " + schemaVersion);
        }
        if (major > SUPPORTED_MAJOR) {
            return DesiredCapabilitySet.unsupported(
                    "不认识的 Desired 主版本: " + schemaVersion + "（支持 " + SUPPORTED_MAJOR + ".x）");
        }
        if (major == 1) {
            // 显式写了 1.x：按 v1 形状处理（等价于无 version）
            return parseV1(root, sourceCode);
        }
        return parseV2(root, schemaVersion);
    }

    // ------------------------------------------------------------------
    // v1
    // ------------------------------------------------------------------
    private static DesiredCapabilitySet parseV1(JsonNode root, String sourceCode) {
        String connector = CONNECTOR_BY_SOURCE.get(sourceCode);
        if (connector == null) {
            return DesiredCapabilitySet.unsupported(
                    "v1 结构无法确定 connectorCode：未登记的 sourceCode=" + sourceCode);
        }
        List<DesiredCapability> caps = new ArrayList<>();
        for (V1Mapping m : V1_MAPPINGS) {
            JsonNode node = root.get(m.field());
            if (node == null || !node.isBoolean()) {
                // 字段缺失或类型非法：不猜测，直接跳过（该能力视为未声明）
                continue;
            }
            boolean on = node.asBoolean();
            caps.add(new DesiredCapability(
                    sourceCode, connector, m.code(), m.category(), m.accessModes(),
                    on ? DesiredCapability.ENABLED : DesiredCapability.DISABLED));
        }
        return DesiredCapabilitySet.of(caps, "1");
    }

    // ------------------------------------------------------------------
    // v2
    // ------------------------------------------------------------------
    private static DesiredCapabilitySet parseV2(JsonNode root, String schemaVersion) {
        JsonNode arr = root.get("capabilities");
        if (arr == null || !arr.isArray()) {
            return DesiredCapabilitySet.unsupported("v2 缺少 capabilities 数组");
        }
        List<DesiredCapability> caps = new ArrayList<>();
        for (JsonNode n : arr) {
            String code = text(n, "code");
            String connector = text(n, "connectorCode");
            String status = text(n, "desiredStatus");
            if (code == null || code.isBlank()) {
                return DesiredCapabilitySet.unsupported("v2 存在缺少 code 的能力条目");
            }
            if (connector == null || connector.isBlank()) {
                return DesiredCapabilitySet.unsupported(
                        "v2 能力 " + code + " 缺少 connectorCode（多 Connector 必须逐条声明）");
            }
            if (!DesiredCapability.ENABLED.equals(status)
                    && !DesiredCapability.DISABLED.equals(status)) {
                return DesiredCapabilitySet.unsupported(
                        "v2 能力 " + code + " 的 desiredStatus 非法: " + status);
            }
            List<String> modes = new ArrayList<>();
            JsonNode m = n.get("accessModes");
            if (m != null && m.isArray()) {
                for (JsonNode x : m) {
                    modes.add(x.asText());
                }
            }
            if (modes.isEmpty()) {
                return DesiredCapabilitySet.unsupported(
                        "v2 能力 " + code + " 的 accessModes 为空");
            }
            caps.add(new DesiredCapability(
                    text(n, "sourceCode"), connector, code,
                    text(n, "category"), List.copyOf(modes), status,
                    text(n, "adapterVersion")));
        }
        return DesiredCapabilitySet.of(caps, schemaVersion);
    }

    // ------------------------------------------------------------------
    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
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
