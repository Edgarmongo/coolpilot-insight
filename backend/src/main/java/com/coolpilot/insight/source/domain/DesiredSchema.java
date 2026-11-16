package com.coolpilot.insight.source.domain;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Desired v2 的**冻结枚举**（与 {@code SOURCE-02-capability-schema.json} 逐字一致）。
 *
 * <p>存在的理由：写接口是唯一能把错误值写进库的入口。如果校验靠"看起来合理"
 * 或"按需排除"，枚举就会在实践中被悄悄扩宽——先写进一个 {@code COMMENT_V2}，
 * 再由下游去适配，最后契约与实际不一致。
 *
 * <p>因此这里把四个枚举**逐字抄写**并显式声明"一字不扩"：
 * 新增能力码必须先改 {@code SOURCE-02-capability-schema.json}，再改这里。
 * 顺序保留 Schema 中的声明顺序，仅用于展示稳定性，不参与判定。
 */
public final class DesiredSchema {

    /** Desired 结构版本（**不是** Observed 的 Schema 版本）。 */
    public static final String VERSION = "2.0";

    /** Java 接受写入的主版本。v1 一律拒写（SOURCE-04-04 §2.7）。 */
    public static final int SUPPORTED_MAJOR = 2;

    /** 平台码（Schema {@code properties.sourceCode}）。 */
    public static final Set<String> SOURCE_CODES = ordered(
            "DOUYIN", "XIAOHONGSHU", "KUAISHOU", "WEB");

    /** 能力码（Schema {@code $defs.capability.properties.code}）。 */
    public static final Set<String> CAPABILITY_CODES = ordered(
            "KEYWORD_SEARCH", "CONTENT_DETAIL", "COMMENT", "COMMENT_REPLY",
            "AUTHOR_PROFILE", "AUTHOR_POSTS", "MEDIA_DOWNLOAD");

    /** 能力分类（Schema {@code $defs.capability.properties.category}）。 */
    public static final Set<String> CATEGORIES = ordered(
            "SEARCH", "CONTENT", "COMMENT", "USER", "MEDIA");

    /** 访问模式（Schema {@code $defs.capability.properties.accessModes.items}）。 */
    public static final Set<String> ACCESS_MODES = ordered("PUBLIC", "AUTHENTICATED");

    /** Desired 自身开关（Desired 私有语义，**不是** {@code implementationStatus}）。 */
    public static final Set<String> DESIRED_STATUSES = ordered(
            DesiredCapability.ENABLED, DesiredCapability.DISABLED);

    /** v2 顶层允许出现的字段。多一个字段就拒写——拼错的字段名不能静默生效。 */
    public static final Set<String> DOCUMENT_FIELDS = ordered("schemaVersion", "capabilities");

    /** v2 能力对象允许出现的字段。 */
    public static final Set<String> CAPABILITY_FIELDS = ordered(
            "sourceCode", "connectorCode", "code", "category",
            "accessModes", "desiredStatus", "adapterVersion");

    private DesiredSchema() {
    }

    private static Set<String> ordered(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(List.of(values)));
    }
}
