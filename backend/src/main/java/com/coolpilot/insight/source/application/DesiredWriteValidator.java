package com.coolpilot.insight.source.application;

import com.coolpilot.insight.source.domain.DesiredCapability;
import com.coolpilot.insight.source.domain.DesiredCapabilitySet;
import com.coolpilot.insight.source.domain.DesiredSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Desired v2 写入校验与规范化（SOURCE-04-06）。
 *
 * <h3>为什么校验要独立成一个纯函数</h3>
 * 写接口是**唯一**能把值写进 {@code ds_source.capability_json} 的入口。
 * 校验若散落在 controller 与 service 里，两处口径迟早会分叉，
 * 于是"接口拒了但服务层放行"这种洞就会出现。这里只有一个判定点。
 *
 * <h3>四个不可让步的原则</h3>
 * <ol>
 *   <li><b>不猜测</b>：{@code sourceCode} 缺失不按路径参数补、类型不对不做字符串化；</li>
 *   <li><b>不默认</b>：{@code accessModes} 为空不塞保守默认值——默认值只属于 v1 迁移，
 *       不属于新写入（新写入必须由业务显式声明，否则"业务允许的访问模式"是系统编的）；</li>
 *   <li><b>不扩枚举</b>：四个枚举逐字来自 {@link DesiredSchema}；</li>
 *   <li><b>不落未知字段</b>：落库的 JSON 由**校验后的模型**重新序列化，
 *       请求体里的额外字段既不会通过校验，也不可能被转存。</li>
 * </ol>
 *
 * <h3>为什么写 v1 被拒绝</h3>
 * SOURCE-04-04 §2.7 明确"旧结构不允许继续产生新写入"。允许写 v1 等于允许
 * 新数据退回旧结构，迁移就白做了。
 */
public final class DesiredWriteValidator {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** connectorCode 是接入实现标识（大写、下划线分隔），如 {@code DOUYIN_PUBLIC}。 */
    private static final Pattern CONNECTOR_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,63}$");

    /** adapterVersion 锁定值：语义化版本，允许预发布后缀。 */
    private static final Pattern ADAPTER_VERSION =
            Pattern.compile("^\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.\\-]+)?$");

    private DesiredWriteValidator() {
    }

    /**
     * 校验结果。
     *
     * @param desired       校验通过后的模型；失败时为空集合（**不含**非法条目）
     * @param canonicalJson 由模型重新序列化的 v2 JSON；失败时为 {@code null}
     * @param errors        全部校验错误；为空表示通过
     */
    public record Result(DesiredCapabilitySet desired, String canonicalJson, List<String> errors) {

        public boolean ok() {
            return errors.isEmpty();
        }

        /** 通过时返回规范化 JSON；未通过直接抛错，避免调用方误用半成品。 */
        public String requireCanonicalJson() {
            if (!ok() || canonicalJson == null) {
                throw new IllegalStateException("校验未通过，不能取规范化结果");
            }
            return canonicalJson;
        }
    }

    /**
     * 校验请求体。
     *
     * @param pathCode 路径上的平台码（{@code /sources/{code}/capabilities}）
     * @param body     请求体
     */
    public static Result validate(String pathCode, JsonNode body) {
        List<String> errors = new ArrayList<>();

        if (body == null || !body.isObject()) {
            return fail("请求体必须是 JSON 对象");
        }

        rejectUnknownFields(body, DesiredSchema.DOCUMENT_FIELDS, "", errors);

        // ---------- schemaVersion：必须是 2.x，且必须显式给出 ----------
        JsonNode versionNode = body.get("schemaVersion");
        if (versionNode == null || versionNode.isNull()) {
            errors.add("缺少 schemaVersion（Desired 结构版本必须显式声明为 " + DesiredSchema.VERSION + "）");
        } else if (!versionNode.isTextual()) {
            errors.add("schemaVersion 必须是字符串");
        } else {
            String version = versionNode.asText();
            int major = DesiredCapabilityParser.majorOf(version);
            if (major <= 0) {
                errors.add("schemaVersion 格式非法: " + version);
            } else if (major < DesiredSchema.SUPPORTED_MAJOR) {
                // 显式拒写 v1：旧结构不得再产生新写入（SOURCE-04-04 §2.7）
                errors.add("不接受 Desired v1 写入（收到 " + version + "）；管理接口只写 "
                        + DesiredSchema.VERSION + ".x");
            } else if (major > DesiredSchema.SUPPORTED_MAJOR) {
                errors.add("不认识的 Desired 主版本: " + version + "（支持 "
                        + DesiredSchema.SUPPORTED_MAJOR + ".x）");
            }
        }

        // ---------- capabilities ----------
        JsonNode capsNode = body.get("capabilities");
        if (capsNode == null || capsNode.isNull()) {
            errors.add("缺少 capabilities 数组（未声明任何能力应显式给空数组）");
            return new Result(DesiredCapabilitySet.empty(), null, List.copyOf(errors));
        }
        if (!capsNode.isArray()) {
            errors.add("capabilities 必须是数组");
            return new Result(DesiredCapabilitySet.empty(), null, List.copyOf(errors));
        }

        List<DesiredCapability> parsed = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int index = 0;
        for (JsonNode node : capsNode) {
            String where = "capabilities[" + index + "]";
            index++;
            if (!node.isObject()) {
                errors.add(where + " 必须是对象");
                continue;
            }
            rejectUnknownFields(node, DesiredSchema.CAPABILITY_FIELDS, where + ".", errors);

            String sourceCode = text(node, "sourceCode", where, errors);
            String connectorCode = text(node, "connectorCode", where, errors);
            String code = text(node, "code", where, errors);
            String category = text(node, "category", where, errors);
            String desiredStatus = text(node, "desiredStatus", where, errors);
            String adapterVersion = optionalText(node, "adapterVersion", where, errors);

            // sourceCode 必须存在**且**与路径一致：
            // 允许不一致就等于允许把 A 平台的声明写进 B 平台的行，三态核对会全错。
            if (sourceCode != null && !sourceCode.equals(pathCode)) {
                errors.add(where + ".sourceCode 与路径不一致: 声明=" + sourceCode + "，路径=" + pathCode);
            }
            if (sourceCode != null) {
                requireMember(sourceCode, DesiredSchema.SOURCE_CODES, where + ".sourceCode", errors);
            }
            if (connectorCode != null && !CONNECTOR_CODE.matcher(connectorCode).matches()) {
                errors.add(where + ".connectorCode 格式非法: " + connectorCode
                        + "（期望大写字母开头、仅含大写字母/数字/下划线）");
            }
            if (code != null) {
                requireMember(code, DesiredSchema.CAPABILITY_CODES, where + ".code", errors);
            }
            if (category != null) {
                requireMember(category, DesiredSchema.CATEGORIES, where + ".category", errors);
            }
            if (desiredStatus != null) {
                requireMember(desiredStatus, DesiredSchema.DESIRED_STATUSES,
                        where + ".desiredStatus", errors);
            }
            if (adapterVersion != null && !ADAPTER_VERSION.matcher(adapterVersion).matches()) {
                errors.add(where + ".adapterVersion 格式非法: " + adapterVersion
                        + "（期望语义化版本，如 1.0.0）");
            }

            List<String> modes = accessModes(node, where, errors);

            // 四元组里的三元已分别校验；这里只拦"重复声明"。
            // 两个方向都会出错：允许顺序覆盖 → 文档顺序变成业务意图；
            // 静默保留第一条 → 用户以为自己改了，其实没改。
            if (connectorCode != null && code != null) {
                String key = connectorCode + "/" + code;
                if (!seen.add(key)) {
                    errors.add(where + " 重复声明: " + key
                            + "（同一 connectorCode + code 只能声明一次）");
                }
            }

            if (hasNull(sourceCode, connectorCode, code, category, desiredStatus)) {
                continue;
            }
            if (modes.isEmpty()) {
                continue;
            }
            parsed.add(new DesiredCapability(sourceCode, connectorCode, code, category,
                    modes, desiredStatus, adapterVersion));
        }

        if (!errors.isEmpty()) {
            return new Result(DesiredCapabilitySet.empty(), null, List.copyOf(errors));
        }

        // 排序只为**幂等**：同一份业务声明无论以什么顺序提交，落库字节一致。
        // 不排序的话，编辑器重排一次数组就会产生一次"内容未变"的写审计。
        parsed.sort(Comparator
                .comparing(DesiredCapability::connectorCode)
                .thenComparing(DesiredCapability::code));

        return new Result(DesiredCapabilitySet.of(parsed, DesiredSchema.VERSION),
                serialize(parsed), List.of());
    }

    // ------------------------------------------------------------------
    // 序列化
    // ------------------------------------------------------------------
    /**
     * 把模型序列化为 v2 JSON（字段顺序固定，与 SOURCE-04-04 §1.1 一致）。
     *
     * <p>刻意不用 {@code writeValueAsString(record)}：字段顺序会跟着 record 声明走，
     * 一旦有人调整组件顺序，落库内容就整体变了，迁移与对账都会失真。
     */
    static String serialize(List<DesiredCapability> capabilities) {
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", DesiredSchema.VERSION);
        ArrayNode arr = root.putArray("capabilities");
        for (DesiredCapability c : capabilities) {
            ObjectNode item = arr.addObject();
            item.put("sourceCode", c.sourceCode());
            item.put("connectorCode", c.connectorCode());
            item.put("code", c.code());
            item.put("category", c.category());
            ArrayNode modes = item.putArray("accessModes");
            c.accessModes().forEach(modes::add);
            item.put("desiredStatus", c.desiredStatus());
            if (c.adapterVersion() != null) {
                item.put("adapterVersion", c.adapterVersion());
            }
        }
        return root.toString();
    }

    // ------------------------------------------------------------------
    // 字段读取（类型严格：不做 toString 兜底）
    // ------------------------------------------------------------------
    private static String text(JsonNode node, String field, String where, List<String> errors) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            errors.add(where + "." + field + " 缺失");
            return null;
        }
        if (!v.isTextual()) {
            errors.add(where + "." + field + " 必须是字符串（收到 " + v.getNodeType() + "）");
            return null;
        }
        String s = v.asText();
        if (s.isBlank()) {
            errors.add(where + "." + field + " 不能为空");
            return null;
        }
        return s;
    }

    private static String optionalText(JsonNode node, String field, String where, List<String> errors) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        return text(node, field, where, errors);
    }

    private static List<String> accessModes(JsonNode node, String where, List<String> errors) {
        JsonNode v = node.get("accessModes");
        if (v == null || v.isNull()) {
            errors.add(where + ".accessModes 缺失");
            return List.of();
        }
        if (!v.isArray()) {
            errors.add(where + ".accessModes 必须是数组");
            return List.of();
        }
        if (v.isEmpty()) {
            errors.add(where + ".accessModes 不能为空（至少声明一种业务允许的访问模式）");
            return List.of();
        }
        List<String> modes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode m : v) {
            if (!m.isTextual()) {
                errors.add(where + ".accessModes 元素必须是字符串（收到 " + m.getNodeType() + "）");
                continue;
            }
            String mode = m.asText();
            if (!DesiredSchema.ACCESS_MODES.contains(mode)) {
                errors.add(where + ".accessModes 含非法取值: " + mode
                        + "（允许 " + DesiredSchema.ACCESS_MODES + "）");
                continue;
            }
            if (!seen.add(mode)) {
                errors.add(where + ".accessModes 重复: " + mode);
                continue;
            }
            modes.add(mode);
        }
        return modes;
    }

    private static void requireMember(String value, Set<String> allowed,
                                      String field, List<String> errors) {
        if (!allowed.contains(value)) {
            errors.add(field + " 非法取值: " + value + "（允许 " + allowed + "）");
        }
    }

    private static void rejectUnknownFields(JsonNode node, Set<String> allowed,
                                            String prefix, List<String> errors) {
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            String name = it.next().getKey();
            if (!allowed.contains(name)) {
                errors.add(prefix + name + " 不是合法字段（允许 " + allowed + "）");
            }
        }
    }

    private static boolean hasNull(String... values) {
        for (String v : values) {
            if (v == null) {
                return true;
            }
        }
        return false;
    }

    private static Result fail(String message) {
        return new Result(DesiredCapabilitySet.empty(), null, List.of(message));
    }
}
