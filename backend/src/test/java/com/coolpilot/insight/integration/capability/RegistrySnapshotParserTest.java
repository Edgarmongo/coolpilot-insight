package com.coolpilot.insight.integration.capability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统一快照的解析与校验测试（SOURCE-04-02 验收 A01/A02/A07）。
 *
 * <p>样本 {@code registry-snapshot-sample.json} 是从**真实运行的**
 * {@code cpi-collection} 容器导出的响应，不是手写的。
 *
 * <p>断言重点：**只接受完整、合法、内部自洽的快照**。
 * 任何一项不满足就整体拒绝——不做局部接受，因为局部接受等于自己制造混合快照。
 */
class RegistrySnapshotParserTest {

    private static String sample() throws IOException {
        try (InputStream in = RegistrySnapshotParserTest.class
                .getResourceAsStream("/registry-snapshot-sample.json")) {
            assertThat(in).as("快照样本必须存在").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 构造一个最小合法快照 JSON。 */
    private static String minimal(String snapshotId, String schemaVersion) {
        return """
                {"code":0,"data":{
                  "snapshotId":"%s",
                  "contractVersion":"1.1",
                  "schemaVersion":"%s",
                  "generatedAt":"2026-11-16T12:00:00Z",
                  "adapters":[{"connectorCode":"X","sourceCode":"DOUYIN",
                               "connectorVersion":"1.0.0","loadStatus":"LOADED","capabilityCount":1}],
                  "capabilities":[{"schemaVersion":"1.1","sourceCode":"DOUYIN","connectorCode":"X",
                                   "connectorVersion":"1.0.0",
                                   "capabilities":[{"code":"COMMENT","category":"COMMENT",
                                     "accessModes":["PUBLIC"],"implementationStatus":"UNVERIFIED",
                                     "contractVersion":"1.0",
                                     "adapterFunction":"app.x.y","semantics":"READ"}]}],
                  "diagnostics":[]
                }}
                """.formatted(snapshotId, schemaVersion);
    }

    private static final String VALID_ID = "sha256:" + "a".repeat(64);

    @Test
    @DisplayName("能解析真实采集服务返回的快照")
    void parsesRealSnapshot() throws IOException {
        var result = RegistrySnapshotParser.parse(sample());

        assertThat(result.ok()).isTrue();
        RegistrySnapshot snap = result.snapshot();
        assertThat(snap.snapshotId()).startsWith("sha256:");
        assertThat(snap.schemaVersion()).startsWith("1.");
        assertThat(snap.adapters()).isNotEmpty();
        assertThat(snap.capabilities()).isNotEmpty();
        assertThat(snap.diagnostics()).isNotNull();
    }

    @Test
    @DisplayName("真实快照里的接入可被按 connectorCode 查到")
    void findsConnectorAndAdapter() throws IOException {
        RegistrySnapshot snap = RegistrySnapshotParser.parse(sample()).snapshot();

        assertThat(snap.findConnector("DOUYIN_PUBLIC")).isNotNull();
        assertThat(snap.findAdapter("DOUYIN_PUBLIC")).isNotNull();
        assertThat(snap.findAdapter("DOUYIN_PUBLIC").isLoaded()).isTrue();
        assertThat(snap.findConnector("DOUYIN_PUBLIC").find("COMMENT")).isNotNull();
    }

    @Test
    @DisplayName("缺少 snapshotId → 拒绝")
    void rejectsMissingSnapshotId() {
        String body = minimal("", "1.1").replace("\"snapshotId\":\"\"", "\"x\":1");
        var result = RegistrySnapshotParser.parse(body);
        assertThat(result.ok()).isFalse();
    }

    @Test
    @DisplayName("snapshotId 用时间戳而非内容哈希 → 拒绝")
    void rejectsTimestampAsSnapshotId() {
        var result = RegistrySnapshotParser.parse(minimal("2026-11-16T12:00:00Z", "1.1"));
        assertThat(result.ok()).isFalse();
        assertThat(result.errors().get(0)).contains("snapshotId");
    }

    @Test
    @DisplayName("Schema 主版本不支持 → 拒绝并标记（不静默忽略）")
    void rejectsUnsupportedSchemaMajor() {
        var result = RegistrySnapshotParser.parse(minimal(VALID_ID, "2.0"));
        assertThat(result.ok()).isFalse();
        assertThat(result.errors().get(0)).contains("主版本");
    }

    @Test
    @DisplayName("Schema 次版本高于 1.0 仍接受（主版本一致）")
    void acceptsMinorVersionBump() {
        assertThat(RegistrySnapshotParser.parse(minimal(VALID_ID, "1.9")).ok()).isTrue();
    }

    @Test
    @DisplayName("缺少 capabilities / diagnostics / adapters 任一数组 → 拒绝")
    void rejectsMissingArrays() {
        assertThat(RegistrySnapshotParser.parse(
                minimal(VALID_ID, "1.1").replace("\"diagnostics\":[]", "\"x\":1")).ok())
                .isFalse();
        assertThat(RegistrySnapshotParser.parse(
                minimal(VALID_ID, "1.1").replace("\"adapters\":[", "\"y\":[")).ok())
                .isFalse();
    }

    @Test
    @DisplayName("空响应 / 非 JSON → 拒绝")
    void rejectsEmptyOrBrokenBody() {
        assertThat(RegistrySnapshotParser.parse("").ok()).isFalse();
        assertThat(RegistrySnapshotParser.parse("{not json").ok()).isFalse();
        assertThat(RegistrySnapshotParser.parse("[]").ok()).isFalse();
    }

    // ------------------------------------------------------------------
    // 内部一致性（本机制的核心）
    // ------------------------------------------------------------------
    @Test
    @DisplayName("诊断为函数不可用，但能力仍出现在清单里 → 拒绝（不一致）")
    void rejectsInconsistentFunctionDiagnostic() {
        String body = minimal(VALID_ID, "1.1").replace("\"diagnostics\":[]", """
                "diagnostics":[{"connectorCode":"X","capabilityCode":"COMMENT",
                                "reason":"INVALID_IMPLEMENTATION","detail":"坏函数",
                                "adapterFunction":"app.x.y"}]
                """);
        var result = RegistrySnapshotParser.parse(body);
        assertThat(result.ok()).isFalse();
        assertThat(result.errors().get(0)).contains("不一致");
    }

    @Test
    @DisplayName("证据类诊断，但能力不在清单里 → 拒绝（不一致）")
    void rejectsInconsistentEvidenceDiagnostic() {
        String body = minimal(VALID_ID, "1.1").replace("\"diagnostics\":[]", """
                "diagnostics":[{"connectorCode":"X","capabilityCode":"MEDIA_DOWNLOAD",
                                "reason":"EVIDENCE_EXPIRED","detail":"证据过期",
                                "adapterFunction":"app.x.y"}]
                """);
        var result = RegistrySnapshotParser.parse(body);
        assertThat(result.ok()).isFalse();
        assertThat(result.errors().get(0)).contains("不一致");
    }

    @Test
    @DisplayName("证据类诊断且能力在清单里 → 接受（这是合法组合）")
    void acceptsEvidenceDiagnosticWithObservedCapability() {
        String body = minimal(VALID_ID, "1.1").replace("\"diagnostics\":[]", """
                "diagnostics":[{"connectorCode":"X","capabilityCode":"COMMENT",
                                "reason":"EVIDENCE_EXPIRED","detail":"证据过期",
                                "adapterFunction":"app.x.y"}]
                """);
        var result = RegistrySnapshotParser.parse(body);
        assertThat(result.ok())
                .as("能力兑现了但证据过期，是合法组合，不应被拒")
                .isTrue();
    }

    @Test
    @DisplayName("Adapter 级诊断（无 capabilityCode）不参与一致性判定")
    void adapterLevelDiagnosticDoesNotAffectConsistency() {
        String body = minimal(VALID_ID, "1.1").replace("\"diagnostics\":[]", """
                "diagnostics":[{"connectorCode":"Y","capabilityCode":null,
                                "reason":"NOT_LOADED","detail":"加载失败",
                                "adapterFunction":null}]
                """);
        assertThat(RegistrySnapshotParser.parse(body).ok()).isTrue();
    }

    @Test
    @DisplayName("能力缺少 adapterFunction → 拒绝")
    void rejectsCapabilityWithoutFunction() {
        String body = minimal(VALID_ID, "1.1")
                .replace("\"adapterFunction\":\"app.x.y\",\"semantics\":\"READ\"",
                         "\"semantics\":\"READ\"");
        var result = RegistrySnapshotParser.parse(body);
        assertThat(result.ok()).isFalse();
    }
}
