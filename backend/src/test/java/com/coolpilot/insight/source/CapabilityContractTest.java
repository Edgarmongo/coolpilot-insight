package com.coolpilot.insight.source;

import com.coolpilot.insight.integration.capability.CapabilityContractParser;
import com.coolpilot.insight.integration.capability.CapabilityDocument;
import com.coolpilot.insight.integration.capability.CapabilityItem;
import com.coolpilot.insight.integration.capability.CapabilityStatus;
import com.coolpilot.insight.integration.capability.ObservedCapabilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Java 消费端的能力契约测试（SOURCE-03 · 03-06）。
 *
 * <p>样本 {@code capabilities-sample.json} 是**从真实采集服务导出的响应**，
 * 不是手写的假数据——契约漂移会直接让这个测试变红。
 *
 * <p>断言重点：
 * <ul>
 *   <li>Java 能准确读懂 Python 的 v1 输出；</li>
 *   <li>旧结构仍然可读（兼容期）；</li>
 *   <li>不认识的主版本 → 标记 UNKNOWN，**不静默忽略、不伪造不可用**；</li>
 *   <li>函数缺失 ⇒ 不可执行（SOURCE-02 §02-03 硬规则）。</li>
 * </ul>
 */
class CapabilityContractTest {

    /** 真实响应样本：采集服务 {@code GET /v1/capabilities} 的原始输出。 */
    private static String sample() throws IOException {
        try (InputStream in = CapabilityContractTest.class
                .getResourceAsStream("/capabilities-sample.json")) {
            assertThat(in).as("能力契约样本必须存在").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("能解析真实采集服务返回的 v1 能力文档")
    void parsesRealCapabilityResponse() throws IOException {
        ObservedCapabilities observed = CapabilityContractParser.parseCapabilities(sample());

        assertThat(observed.schemaVersion()).isEqualTo("1.0");
        assertThat(observed.schemaMajor()).isEqualTo(1);
        assertThat(observed.schemaSupported()).isTrue();
        assertThat(observed.isUnknown()).isFalse();
        assertThat(observed.warnings()).isEmpty();

        assertThat(observed.documents()).hasSize(1);
        CapabilityDocument doc = observed.findByConnector("DOUYIN_PUBLIC");
        assertThat(doc).isNotNull();
        assertThat(doc.sourceCode()).isEqualTo("DOUYIN");
        assertThat(doc.connectorVersion()).isEqualTo("1.0.0");
        assertThat(doc.capabilities()).hasSize(4);
    }

    @Test
    @DisplayName("每条能力都带真实函数引用与契约版本")
    void everyCapabilityBindsFunction() throws IOException {
        CapabilityDocument doc = CapabilityContractParser.parseCapabilities(sample())
                .findByConnector("DOUYIN_PUBLIC");

        assertThat(doc).isNotNull();
        for (CapabilityItem item : doc.capabilities()) {
            assertThat(item.hasBoundFunction())
                    .as("%s 必须绑定真实可调用函数", item.code()).isTrue();
            assertThat(item.adapterFunction()).contains(".");
            assertThat(item.contractVersion()).isNotBlank();
            assertThat(CapabilityStatus.isKnown(item.implementationStatus()))
                    .as("%s 的实现状态必须在正式枚举内", item.code()).isTrue();
        }
    }

    @Test
    @DisplayName("经真实平台验证的能力才可执行；未验证的一律不可执行")
    void verifiedCapabilityIsExecutable() throws IOException {
        CapabilityDocument doc = CapabilityContractParser.parseCapabilities(sample())
                .findByConnector("DOUYIN_PUBLIC");
        assertThat(doc).isNotNull();

        CapabilityItem comment = doc.find("COMMENT");
        assertThat(comment).isNotNull();
        assertThat(comment.implementationStatus()).isEqualTo(CapabilityStatus.VERIFIED);
        assertThat(comment.verifiedAt()).isNotBlank();
        assertThat(comment.isVerified()).isTrue();
        assertThat(comment.isExecutable()).isTrue();

        CapabilityItem reply = doc.find("COMMENT_REPLY");
        assertThat(reply).isNotNull();
        assertThat(reply.implementationStatus()).isEqualTo(CapabilityStatus.VERIFIED);

        CapabilityItem detail = doc.find("CONTENT_DETAIL");
        assertThat(detail).isNotNull();
        assertThat(detail.implementationStatus()).isEqualTo(CapabilityStatus.VERIFIED);
    }

    @Test
    @DisplayName("关键词搜索仍未验证 → 不可执行，且不得被当成可用")
    void unverifiedCapabilityIsNotExecutable() throws IOException {
        CapabilityDocument doc = CapabilityContractParser.parseCapabilities(sample())
                .findByConnector("DOUYIN_PUBLIC");
        assertThat(doc).isNotNull();

        CapabilityItem search = doc.find("KEYWORD_SEARCH");
        assertThat(search).isNotNull();
        assertThat(search.implementationStatus()).isEqualTo(CapabilityStatus.UNVERIFIED);
        assertThat(search.verifiedAt()).isNull();
        assertThat(search.isVerified()).isFalse();
        assertThat(search.isExecutable()).isFalse();

        // 旧布尔口径同样为 false——两套字段不能打架
        List<CapabilityContractParser.LegacyCapability> legacy =
                CapabilityContractParser.parseLegacyItems(sample());
        CapabilityContractParser.LegacyCapability douyin = legacy.stream()
                .filter(i -> "DOUYIN".equals(i.sourceCode())).findFirst().orElseThrow();
        assertThat(douyin.keywordSearch()).isFalse();
        assertThat(douyin.comments()).isTrue();
        assertThat(douyin.status()).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("函数引用缺失 ⇒ 即使状态是 VERIFIED 也不可执行")
    void missingFunctionMeansNotExecutable() {
        CapabilityItem broken = new CapabilityItem(
                "COMMENT", "COMMENT", "缺函数", List.of("PUBLIC"),
                CapabilityStatus.VERIFIED, "1.0", null, "2026-11-16T00:00:00Z",
                null, null);

        assertThat(broken.hasBoundFunction()).isFalse();
        assertThat(broken.statusAllowsExecution()).isTrue();
        assertThat(broken.isExecutable()).isFalse();
    }

    @Test
    @DisplayName("旧结构 items 仍然可读")
    void legacyItemsStillParseable() throws IOException {
        List<CapabilityContractParser.LegacyCapability> legacy =
                CapabilityContractParser.parseLegacyItems(sample());

        assertThat(legacy).hasSize(2);
        CapabilityContractParser.LegacyCapability stub = legacy.stream()
                .filter(i -> "STUB".equals(i.sourceCode())).findFirst().orElseThrow();
        assertThat(stub.comments()).isFalse();
        assertThat(stub.keywordSearch()).isFalse();
        assertThat(stub.status()).isEqualTo("PENDING");
        assertThat(stub.note()).contains("链路验证");
    }

    @Test
    @DisplayName("不认识的主版本 → 拒绝解析并标记 UNKNOWN，不静默忽略")
    void unknownSchemaMajorIsMarkedUnknown() {
        String body = """
                {"code":0,"schemaVersion":"2.0","connectors":[
                  {"schemaVersion":"2.0","sourceCode":"DOUYIN","connectorCode":"DOUYIN_PUBLIC",
                   "connectorVersion":"2.0.0","capabilities":[]}]}
                """;

        ObservedCapabilities observed = CapabilityContractParser.parseCapabilities(body);

        assertThat(observed.schemaSupported()).isFalse();
        assertThat(observed.isUnknown()).isTrue();
        assertThat(observed.documents()).isEmpty();
        assertThat(observed.warnings()).isNotEmpty();
    }

    @Test
    @DisplayName("响应为空或非法 → 标记 UNKNOWN，而不是伪造为不可用")
    void malformedResponseIsUnknownNotUnavailable() {
        ObservedCapabilities empty = CapabilityContractParser.parseCapabilities("");
        assertThat(empty.isUnknown()).isTrue();
        assertThat(empty.documents()).isEmpty();

        ObservedCapabilities broken = CapabilityContractParser.parseCapabilities("{not json");
        assertThat(broken.isUnknown()).isTrue();
        assertThat(broken.documents()).isEmpty();
    }

    @Test
    @DisplayName("能力限额与重试策略被正确解析")
    void limitsAndRetryPolicyParsed() throws IOException {
        CapabilityDocument doc = CapabilityContractParser.parseCapabilities(sample())
                .findByConnector("DOUYIN_PUBLIC");
        assertThat(doc).isNotNull();

        CapabilityItem comment = doc.find("COMMENT");
        assertThat(comment).isNotNull();
        assertThat(comment.limits()).isNotNull();
        assertThat(comment.limits().maxComments()).isEqualTo(300);
        assertThat(comment.limits().maxPageSize()).isEqualTo(20);
        assertThat(comment.limits().supportsPagination()).isTrue();
        assertThat(comment.retryPolicy()).isNotNull();
        assertThat(comment.retryPolicy().maxAttempts()).isEqualTo(3);
        assertThat(comment.retryPolicy().retryableErrors())
                .contains("NETWORK", "RATE_LIMIT", "DATA", "SYSTEM");
    }
}
