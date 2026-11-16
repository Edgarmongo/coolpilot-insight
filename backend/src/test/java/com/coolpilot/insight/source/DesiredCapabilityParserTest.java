package com.coolpilot.insight.source;

import com.coolpilot.insight.source.application.DesiredCapabilityParser;
import com.coolpilot.insight.source.domain.DesiredCapability;
import com.coolpilot.insight.source.domain.DesiredCapabilitySet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Desired 解析测试（SOURCE-04-04 §2 的 v1→v2 兼容规则）。
 *
 * <p>库中数据仍是 v1，而核对层按 v2 形状工作——双读是 04-03 能落地的前提。
 */
class DesiredCapabilityParserTest {

    /** 库中 DOUYIN 的真实 v1 值（2026-11-16 实查）。 */
    private static final String REAL_V1 =
            "{\"comments\": true, \"commentStatus\": \"AVAILABLE\", \"contentDetail\": true,"
                    + " \"keywordSearch\": false, \"keywordStatus\": \"PENDING\"}";

    @Test
    @DisplayName("v1：真实 capability_json 被确定性映射为 v2 形状")
    void parsesRealV1() {
        DesiredCapabilitySet set = DesiredCapabilityParser.parse(REAL_V1, "DOUYIN");

        assertThat(set.supported()).isTrue();
        assertThat(set.schemaVersion()).isEqualTo("1");
        assertThat(set.capabilities()).hasSize(3);

        assertThat(set.declares("DOUYIN_PUBLIC", "COMMENT")).isTrue();
        assertThat(set.declares("DOUYIN_PUBLIC", "CONTENT_DETAIL")).isTrue();
        assertThat(set.declares("DOUYIN_PUBLIC", "KEYWORD_SEARCH"))
                .as("v1 里 keywordSearch=false，映射为 DISABLED，等同于未声明")
                .isFalse();
    }

    @Test
    @DisplayName("v1：connectorCode 由 sourceCode 显式映射，不用 adapter_code 拼")
    void v1ConnectorMappingUsesSourceCode() {
        DesiredCapabilitySet set = DesiredCapabilityParser.parse(REAL_V1, "DOUYIN");
        assertThat(set.capabilities())
                .allSatisfy(c -> assertThat(c.connectorCode()).isEqualTo("DOUYIN_PUBLIC"));
    }

    @Test
    @DisplayName("v1：accessModes 取保守默认（COMMENT 双模式，其余 PUBLIC）")
    void v1AccessModeDefaults() {
        DesiredCapabilitySet set = DesiredCapabilityParser.parse(REAL_V1, "DOUYIN");

        DesiredCapability comment = set.find("DOUYIN_PUBLIC", "COMMENT").orElseThrow();
        assertThat(comment.accessModes()).containsExactly("PUBLIC", "AUTHENTICATED");

        DesiredCapability detail = set.find("DOUYIN_PUBLIC", "CONTENT_DETAIL").orElseThrow();
        assertThat(detail.accessModes()).containsExactly("PUBLIC");
    }

    @Test
    @DisplayName("v1：未登记的 sourceCode → supported=false（不猜测 connector）")
    void v1UnknownSourceUnsupported() {
        DesiredCapabilitySet set = DesiredCapabilityParser.parse(REAL_V1, "UNKNOWN_PLATFORM");
        assertThat(set.supported()).isFalse();
        assertThat(set.error()).contains("connectorCode");
    }

    @Test
    @DisplayName("v1：字段缺失时该能力视为未声明，不报错")
    void v1MissingFieldIsSkipped() {
        DesiredCapabilitySet set = DesiredCapabilityParser.parse("{\"comments\": true}", "DOUYIN");
        assertThat(set.supported()).isTrue();
        assertThat(set.capabilities()).hasSize(1);
        assertThat(set.declares("DOUYIN_PUBLIC", "CONTENT_DETAIL")).isFalse();
    }

    @Test
    @DisplayName("v2：直接解析，字段完整")
    void parsesV2() {
        String v2 = """
                {"schemaVersion":"2.0","capabilities":[
                  {"sourceCode":"DOUYIN","connectorCode":"DOUYIN_PUBLIC","code":"COMMENT",
                   "category":"COMMENT","accessModes":["PUBLIC"],"desiredStatus":"ENABLED"},
                  {"sourceCode":"DOUYIN","connectorCode":"DOUYIN_PUBLIC","code":"KEYWORD_SEARCH",
                   "category":"SEARCH","accessModes":["PUBLIC"],"desiredStatus":"DISABLED"}
                ]}""";
        DesiredCapabilitySet set = DesiredCapabilityParser.parse(v2, "DOUYIN");

        assertThat(set.supported()).isTrue();
        assertThat(set.schemaVersion()).isEqualTo("2.0");
        assertThat(set.capabilities()).hasSize(2);
        assertThat(set.declares("DOUYIN_PUBLIC", "COMMENT")).isTrue();
        assertThat(set.declares("DOUYIN_PUBLIC", "KEYWORD_SEARCH")).isFalse();
    }

    @Test
    @DisplayName("v2：adapterVersion 可选，声明即锁定")
    void v2AdapterVersionOptional() {
        String v2 = """
                {"schemaVersion":"2.0","capabilities":[
                  {"sourceCode":"DOUYIN","connectorCode":"DOUYIN_PUBLIC","code":"COMMENT",
                   "category":"COMMENT","accessModes":["PUBLIC"],"desiredStatus":"ENABLED",
                   "adapterVersion":"1.0.0"}
                ]}""";
        DesiredCapabilitySet set = DesiredCapabilityParser.parse(v2, "DOUYIN");
        assertThat(set.find("DOUYIN_PUBLIC", "COMMENT").orElseThrow().adapterVersion())
                .isEqualTo("1.0.0");
    }

    @Test
    @DisplayName("v2：主版本 ≥3 → supported=false")
    void v2FutureMajorUnsupported() {
        DesiredCapabilitySet set = DesiredCapabilityParser.parse(
                "{\"schemaVersion\":\"3.0\",\"capabilities\":[]}", "DOUYIN");
        assertThat(set.supported()).isFalse();
        assertThat(set.error()).contains("主版本");
    }

    @Test
    @DisplayName("v2：缺 connectorCode / desiredStatus 非法 / accessModes 空 → 整体拒绝")
    void v2InvalidEntriesRejected() {
        assertThat(DesiredCapabilityParser.parse("""
                {"schemaVersion":"2.0","capabilities":[
                  {"code":"COMMENT","category":"COMMENT","accessModes":["PUBLIC"],
                   "desiredStatus":"ENABLED"}]}""", "DOUYIN").supported())
                .as("多 Connector 必须逐条声明 connectorCode").isFalse();

        assertThat(DesiredCapabilityParser.parse("""
                {"schemaVersion":"2.0","capabilities":[
                  {"connectorCode":"DOUYIN_PUBLIC","code":"COMMENT","category":"COMMENT",
                   "accessModes":["PUBLIC"],"desiredStatus":"MAYBE"}]}""", "DOUYIN").supported())
                .as("desiredStatus 只能是 ENABLED/DISABLED").isFalse();

        assertThat(DesiredCapabilityParser.parse("""
                {"schemaVersion":"2.0","capabilities":[
                  {"connectorCode":"DOUYIN_PUBLIC","code":"COMMENT","category":"COMMENT",
                   "accessModes":[],"desiredStatus":"ENABLED"}]}""", "DOUYIN").supported())
                .as("accessModes 不能为空").isFalse();
    }

    @Test
    @DisplayName("非法 JSON / 空值不抛异常，而是 supported=false / empty")
    void malformedInputHandling() {
        assertThat(DesiredCapabilityParser.parse("{not json", "DOUYIN").supported()).isFalse();
        assertThat(DesiredCapabilityParser.parse("[]", "DOUYIN").supported()).isFalse();

        assertThat(DesiredCapabilityParser.parse(null, "DOUYIN").supported()).isTrue();
        assertThat(DesiredCapabilityParser.parse("", "DOUYIN").capabilities()).isEmpty();
    }
}
