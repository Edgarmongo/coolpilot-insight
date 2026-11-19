package com.coolpilot.insight.source;

import com.coolpilot.insight.source.application.DesiredWriteValidator;
import com.coolpilot.insight.source.domain.DesiredCapability;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DesiredWriteValidator} 的写入闸门测试（SOURCE-04-06）。
 *
 * <p>这组测试的存在理由：写接口是把错误写进库的**唯一**通道。
 * 校验一旦漏掉一个分支，错误值就会变成"已声明的业务事实"，
 * 而下游（Effective 推导、采集派发）会把它当真的用。
 *
 * <p>因此每个用例都对应一条**具体的拒绝理由**，而不是笼统的"非法输入"。
 */
class DesiredWriteValidatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PATH = "DOUYIN";

    private static JsonNode json(String raw) {
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String cap(String connector, String code, String category,
                              String modes, String status) {
        return "{\"sourceCode\":\"DOUYIN\",\"connectorCode\":\"" + connector + "\","
                + "\"code\":\"" + code + "\",\"category\":\"" + category + "\","
                + "\"accessModes\":" + modes + ",\"desiredStatus\":\"" + status + "\"}";
    }

    private static String doc(String... caps) {
        return "{\"schemaVersion\":\"2.0\",\"capabilities\":[" + String.join(",", caps) + "]}";
    }

    private static DesiredWriteValidator.Result validate(String raw) {
        return DesiredWriteValidator.validate(PATH, json(raw));
    }

    // ------------------------------------------------------------------
    // 通过
    // ------------------------------------------------------------------
    @Test
    @DisplayName("合法 v2 → 通过，规范化 JSON 字段顺序固定")
    void acceptsValidV2() {
        var r = validate(doc(cap("DOUYIN_PUBLIC", "COMMENT", "COMMENT",
                "[\"PUBLIC\",\"AUTHENTICATED\"]", "ENABLED")));

        assertThat(r.ok()).isTrue();
        assertThat(r.desired().capabilities()).hasSize(1);
        assertThat(r.errors()).isEmpty();
        // 字段顺序固定，便于人工对账与按字节比较
        assertThat(r.canonicalJson()).isEqualTo(
                "{\"schemaVersion\":\"2.0\",\"capabilities\":[{"
                        + "\"sourceCode\":\"DOUYIN\","
                        + "\"connectorCode\":\"DOUYIN_PUBLIC\","
                        + "\"code\":\"COMMENT\","
                        + "\"category\":\"COMMENT\","
                        + "\"accessModes\":[\"PUBLIC\",\"AUTHENTICATED\"],"
                        + "\"desiredStatus\":\"ENABLED\"}]}");
    }

    @Test
    @DisplayName("空 capabilities 数组 → 通过（显式声明\"不提供任何能力\"）")
    void acceptsEmptyCapabilityList() {
        var r = validate("{\"schemaVersion\":\"2.0\",\"capabilities\":[]}");

        assertThat(r.ok()).isTrue();
        assertThat(r.desired().capabilities()).isEmpty();
        assertThat(r.canonicalJson())
                .isEqualTo("{\"schemaVersion\":\"2.0\",\"capabilities\":[]}");
    }

    @Test
    @DisplayName("顺序不同、内容相同 → 规范化结果逐字节一致（幂等）")
    void canonicalJsonIsOrderIndependent() {
        String a = doc(
                cap("DOUYIN_PUBLIC", "COMMENT", "COMMENT", "[\"PUBLIC\"]", "ENABLED"),
                cap("DOUYIN_PUBLIC", "CONTENT_DETAIL", "CONTENT", "[\"PUBLIC\"]", "ENABLED"));
        String b = doc(
                cap("DOUYIN_PUBLIC", "CONTENT_DETAIL", "CONTENT", "[\"PUBLIC\"]", "ENABLED"),
                cap("DOUYIN_PUBLIC", "COMMENT", "COMMENT", "[\"PUBLIC\"]", "ENABLED"));

        assertThat(validate(a).canonicalJson()).isEqualTo(validate(b).canonicalJson());
    }

    @Test
    @DisplayName("adapterVersion 显式声明 → 落库保留（声明即锁定）")
    void keepsAdapterVersionWhenDeclared() {
        String raw = "{\"schemaVersion\":\"2.0\",\"capabilities\":[{"
                + "\"sourceCode\":\"DOUYIN\",\"connectorCode\":\"DOUYIN_PUBLIC\","
                + "\"code\":\"COMMENT\",\"category\":\"COMMENT\","
                + "\"accessModes\":[\"PUBLIC\"],\"desiredStatus\":\"ENABLED\","
                + "\"adapterVersion\":\"1.0.0\"}]}";

        var r = validate(raw);

        assertThat(r.ok()).isTrue();
        assertThat(r.desired().capabilities().get(0).adapterVersion()).isEqualTo("1.0.0");
        assertThat(r.canonicalJson()).contains("\"adapterVersion\":\"1.0.0\"");
    }

    // ------------------------------------------------------------------
    // 结构版本
    // ------------------------------------------------------------------
    @Test
    @DisplayName("写 v1 → 拒绝（旧结构不得再产生新写入）")
    void rejectsV1Write() {
        var r = validate("{\"schemaVersion\":\"1.0\",\"capabilities\":[]}");

        assertThat(r.ok()).isFalse();
        assertThat(r.errors()).anySatisfy(e -> assertThat(e).contains("不接受 Desired v1 写入"));
        assertThat(r.canonicalJson()).isNull();
    }

    @Test
    @DisplayName("不写 schemaVersion → 拒绝（不允许隐式按 v1 处理）")
    void rejectsMissingSchemaVersion() {
        var r = validate("{\"capabilities\":[]}");

        assertThat(r.ok()).isFalse();
        assertThat(r.errors()).anySatisfy(e -> assertThat(e).contains("缺少 schemaVersion"));
    }

    @Test
    @DisplayName("主版本 3 → 拒绝（不认识的结构不得写入，避免写出自己读不懂的东西）")
    void rejectsUnknownMajor() {
        var r = validate("{\"schemaVersion\":\"3.0\",\"capabilities\":[]}");

        assertThat(r.ok()).isFalse();
        assertThat(r.errors()).anySatisfy(e -> assertThat(e).contains("不认识的 Desired 主版本"));
    }

    @Test
    @DisplayName("capabilities 不是数组 → 拒绝")
    void rejectsNonArrayCapabilities() {
        var r = validate("{\"schemaVersion\":\"2.0\",\"capabilities\":{}}");

        assertThat(r.ok()).isFalse();
        assertThat(r.errors()).anySatisfy(e -> assertThat(e).contains("capabilities 必须是数组"));
    }

    @Test
    @DisplayName("顶层出现未知字段 → 拒绝（拼错的字段名不能静默生效）")
    void rejectsUnknownTopLevelField() {
        var r = validate("{\"schemaVersion\":\"2.0\",\"capabilities\":[],\"extra\":1}");

        assertThat(r.ok()).isFalse();
        assertThat(r.errors()).anySatisfy(e -> assertThat(e).contains("extra 不是合法字段"));
    }

    @Test
    @DisplayName("能力对象出现未知字段 → 拒绝")
    void rejectsUnknownCapabilityField() {
        String raw = "{\"schemaVersion\":\"2.0\",\"capabilities\":[{"
                + "\"sourceCode\":\"DOUYIN\",\"connectorCode\":\"DOUYIN_PUBLIC\","
                + "\"code\":\"COMMENT\",\"category\":\"COMMENT\","
                + "\"accessModes\":[\"PUBLIC\"],\"desiredStatus\":\"ENABLED\","
                + "\"implementationStatus\":\"VERIFIED\"}]}";

        var r = validate(raw);

        assertThat(r.ok()).isFalse();
        assertThat(r.errors()).anySatisfy(e ->
                assertThat(e).contains("implementationStatus 不是合法字段"));
    }

    // ------------------------------------------------------------------
    // 字段合法性
    // ------------------------------------------------------------------
    @Test
    @DisplayName("sourceCode 与路径不一致 → 拒绝（否则会把 A 平台声明写进 B 平台的行）")
    void rejectsSourceCodeMismatch() {
        String raw = doc("{\"sourceCode\":\"XIAOHONGSHU\",\"connectorCode\":\"DOUYIN_PUBLIC\","
                + "\"code\":\"COMMENT\",\"category\":\"COMMENT\","
                + "\"accessModes\":[\"PUBLIC\"],\"desiredStatus\":\"ENABLED\"}");

        var r = validate(raw);

        assertThat(r.ok()).isFalse();
        assertThat(r.errors()).anySatisfy(e -> assertThat(e).contains("与路径不一致"));
    }

    @Test
    @DisplayName("能力码不在冻结枚举内 → 拒绝（不得就地扩枚举）")
    void rejectsUnknownCapabilityCode() {
        var r = validate(doc(cap("DOUYIN_PUBLIC", "COMMENT_V2", "COMMENT",
                "[\"PUBLIC\"]", "ENABLED")));

        assertThat(r.ok()).isFalse();
        assertThat(r.errors()).anySatisfy(e -> assertThat(e).contains("code 非法取值: COMMENT_V2"));
    }

    @Test
    @DisplayName("category 非法 → 拒绝")
    void rejectsUnknownCategory() {
        var r = validate(doc(cap("DOUYIN_PUBLIC", "COMMENT", "REACTION",
                "[\"PUBLIC\"]", "ENABLED")));

        assertThat(r.ok()).isFalse();
        assertThat(r.errors()).anySatisfy(e -> assertThat(e).contains("category 非法取值"));
    }

    @Test
    @DisplayName("desiredStatus 用 v1 的 PENDING → 拒绝（旧状态词不得回到 Desired）")
    void rejectsLegacyStatusWord() {
        var r = validate(doc(cap("DOUYIN_PUBLIC", "KEYWORD_SEARCH", "SEARCH",
                "[\"PUBLIC\"]", "PENDING")));

        assertThat(r.ok()).isFalse();
        assertThat(r.errors()).anySatisfy(e -> assertThat(e).contains("desiredStatus 非法取值: PENDING"));
    }

    @Test
    @DisplayName("accessModes 空数组 → 拒绝（不替业务填保守默认值）")
    void rejectsEmptyAccessModes() {
        var r = validate(doc(cap("DOUYIN_PUBLIC", "COMMENT", "COMMENT", "[]", "ENABLED")));

        assertThat(r.ok()).isFalse();
        assertThat(r.errors()).anySatisfy(e -> assertThat(e).contains("accessModes 不能为空"));
    }

    @Test
    @DisplayName("accessModes 含非法取值 / 重复项 → 拒绝")
    void rejectsBadAccessModes() {
        assertThat(validate(doc(cap("DOUYIN_PUBLIC", "COMMENT", "COMMENT",
                "[\"PUBLIC\",\"COOKIE\"]", "ENABLED"))).errors())
                .anySatisfy(e -> assertThat(e).contains("accessModes 含非法取值: COOKIE"));

        assertThat(validate(doc(cap("DOUYIN_PUBLIC", "COMMENT", "COMMENT",
                "[\"PUBLIC\",\"PUBLIC\"]", "ENABLED"))).errors())
                .anySatisfy(e -> assertThat(e).contains("accessModes 重复: PUBLIC"));
    }

    @Test
    @DisplayName("connectorCode 格式非法 → 拒绝（小写平台码不是 connectorCode）")
    void rejectsBadConnectorCode() {
        var r = validate(doc(cap("douyin", "COMMENT", "COMMENT", "[\"PUBLIC\"]", "ENABLED")));

        assertThat(r.ok()).isFalse();
        assertThat(r.errors()).anySatisfy(e -> assertThat(e).contains("connectorCode 格式非法"));
    }

    @Test
    @DisplayName("adapterVersion 非语义化版本 → 拒绝")
    void rejectsBadAdapterVersion() {
        String raw = "{\"schemaVersion\":\"2.0\",\"capabilities\":[{"
                + "\"sourceCode\":\"DOUYIN\",\"connectorCode\":\"DOUYIN_PUBLIC\","
                + "\"code\":\"COMMENT\",\"category\":\"COMMENT\","
                + "\"accessModes\":[\"PUBLIC\"],\"desiredStatus\":\"ENABLED\","
                + "\"adapterVersion\":\"latest\"}]}";

        var r = validate(raw);

        assertThat(r.ok()).isFalse();
        assertThat(r.errors()).anySatisfy(e -> assertThat(e).contains("adapterVersion 格式非法"));
    }

    @Test
    @DisplayName("类型错误（desiredStatus 传布尔）→ 拒绝，不做字符串化")
    void rejectsWrongJsonType() {
        String raw = "{\"schemaVersion\":\"2.0\",\"capabilities\":[{"
                + "\"sourceCode\":\"DOUYIN\",\"connectorCode\":\"DOUYIN_PUBLIC\","
                + "\"code\":\"COMMENT\",\"category\":\"COMMENT\","
                + "\"accessModes\":[\"PUBLIC\"],\"desiredStatus\":true}]}";

        var r = validate(raw);

        assertThat(r.ok()).isFalse();
        assertThat(r.errors()).anySatisfy(e -> assertThat(e)
                .contains("desiredStatus 必须是字符串"));
        // 若做了 toString 兜底，会变成 "true" —— 那正是"猜测字段含义"
        assertThat(r.canonicalJson()).isNull();
    }

    @Test
    @DisplayName("同一 connectorCode + code 重复声明 → 拒绝（不让数组顺序决定业务意图）")
    void rejectsDuplicateDeclaration() {
        var r = validate(doc(
                cap("DOUYIN_PUBLIC", "COMMENT", "COMMENT", "[\"PUBLIC\"]", "ENABLED"),
                cap("DOUYIN_PUBLIC", "COMMENT", "COMMENT", "[\"PUBLIC\"]", "DISABLED")));

        assertThat(r.ok()).isFalse();
        assertThat(r.errors()).anySatisfy(e -> assertThat(e).contains("重复声明: DOUYIN_PUBLIC/COMMENT"));
    }

    @Test
    @DisplayName("不同 connectorCode 下同码能力 → 允许（多接入是合法形态）")
    void allowsSameCodeUnderDifferentConnectors() {
        var r = validate(doc(
                cap("DOUYIN_PUBLIC", "COMMENT", "COMMENT", "[\"PUBLIC\"]", "ENABLED"),
                cap("DOUYIN_SESSION", "COMMENT", "COMMENT", "[\"AUTHENTICATED\"]", "ENABLED")));

        assertThat(r.ok()).isTrue();
        assertThat(r.desired().capabilities()).hasSize(2);
    }

    @Test
    @DisplayName("请求体不是对象 → 拒绝")
    void rejectsNonObjectBody() {
        var r = DesiredWriteValidator.validate(PATH, json("[]"));

        assertThat(r.ok()).isFalse();
        assertThat(r.errors()).containsExactly("请求体必须是 JSON 对象");
    }

    @Test
    @DisplayName("校验失败时不得提供规范化结果（防止半成品落库）")
    void failedResultRefusesCanonicalJson() {
        var r = validate("{\"capabilities\":[]}");

        assertThatThrownBy(r::requireCanonicalJson)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("多个错误一次性返回，不是遇到第一个就停")
    void reportsAllErrorsAtOnce() {
        String raw = doc(cap("douyin", "NOPE", "REACTION", "[]", "PENDING"));

        var r = validate(raw);

        assertThat(r.ok()).isFalse();
        assertThat(r.errors()).hasSizeGreaterThanOrEqualTo(4);
    }

    /** 生成规范化能力对象，供断言枚举成员比较。 */
    @Test
    @DisplayName("DESIRED 状态枚举只有 ENABLED / DISABLED")
    void desiredStatusEnumIsClosed() {
        assertThat(com.coolpilot.insight.source.domain.DesiredSchema.DESIRED_STATUSES)
                .containsExactly(DesiredCapability.ENABLED, DesiredCapability.DISABLED);
    }
}
