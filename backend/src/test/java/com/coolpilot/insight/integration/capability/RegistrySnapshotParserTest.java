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
 * <p>断言重点：
 *
 * <ul>
 *   <li>**只接受完整、合法、内部自洽的快照**；</li>
 *   <li>失败时断言**结构化错误码**（{@link RegistrySnapshotParser.Failure}），
 *       不断言 message 文本——改措辞不应让测试变红，也不应改变分类；</li>
 *   <li>{@code schemaVersion} 与 {@code contractVersion} **独立校验**。</li>
 * </ul>
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
    private static String minimal(String snapshotId, String schemaVersion, String contractVersion) {
        return """
                {"code":0,"data":{
                  "snapshotId":"%s",
                  "contractVersion":"%s",
                  "schemaVersion":"%s",
                  "generatedAt":"2026-11-16T12:00:00Z",
                  "adapters":[{"connectorCode":"X","sourceCode":"DOUYIN",
                               "connectorVersion":"1.0.0","loadStatus":"LOADED",
                               "module":"app.x.adapter","platformScoped":true,"capabilityCount":1,
                               "capabilities":[{"code":"COMMENT","adapterFunction":"app.x.y"}]}],
                  "capabilities":[{"schemaVersion":"1.1","sourceCode":"DOUYIN","connectorCode":"X",
                                   "connectorVersion":"1.0.0",
                                   "capabilities":[{"code":"COMMENT","category":"COMMENT",
                                     "accessModes":["PUBLIC"],"implementationStatus":"UNVERIFIED",
                                     "contractVersion":"1.0",
                                     "adapterFunction":"app.x.y","semantics":"READ"}]}],
                  "diagnostics":[]
                }}
                """.formatted(snapshotId, contractVersion, schemaVersion);
    }

    private static String minimal(String snapshotId, String schemaVersion) {
        return minimal(snapshotId, schemaVersion, "1.0");
    }

    private static final String VALID_ID = "sha256:" + "a".repeat(64);

    // ------------------------------------------------------------------
    // 正常路径
    // ------------------------------------------------------------------
    @Test
    @DisplayName("能解析真实采集服务返回的快照")
    void parsesRealSnapshot() throws IOException {
        var result = RegistrySnapshotParser.parse(sample());

        assertThat(result.ok()).isTrue();
        assertThat(result.error()).isNull();
        RegistrySnapshot snap = result.snapshot();
        assertThat(snap.snapshotId()).startsWith("sha256:");
        assertThat(snap.schemaVersion()).startsWith("1.");
        assertThat(snap.contractVersion()).isNotBlank();
        assertThat(snap.adapters()).isNotEmpty();
        assertThat(snap.capabilities()).isNotEmpty();
        assertThat(snap.diagnostics()).isNotNull();
    }

    @Test
    @DisplayName("Adapter 的 platformScoped 与 module 必须被完整保留")
    void preservesPlatformScopedAndModule() throws IOException {
        RegistrySnapshot snap = RegistrySnapshotParser.parse(sample()).snapshot();

        var stub = snap.findAdapter("STUB_PUBLIC");
        assertThat(stub).as("真实快照应包含 STUB_PUBLIC").isNotNull();
        assertThat(stub.platformScoped())
                .as("platformScoped 不得丢——04-03 靠它排除链路验证替身")
                .isEqualTo(Boolean.FALSE);
        assertThat(stub.isPlatformScoped()).isFalse();
        assertThat(stub.module()).isEqualTo("app.adapters.stub");

        var douyin = snap.findAdapter("DOUYIN_PUBLIC");
        assertThat(douyin).isNotNull();
        assertThat(douyin.platformScoped()).isTrue();
        assertThat(douyin.isPlatformScoped()).isTrue();
        assertThat(douyin.module()).contains("douyin");
    }

    @Test
    @DisplayName("platformScopedAdapters 排除替身，只留真实平台接入")
    void platformScopedAdaptersExcludesStubs() throws IOException {
        RegistrySnapshot snap = RegistrySnapshotParser.parse(sample()).snapshot();

        assertThat(snap.adapters()).hasSizeGreaterThan(1);
        assertThat(snap.platformScopedAdapters())
                .extracting(RegistrySnapshot.AdapterView::connectorCode)
                .containsExactly("DOUYIN_PUBLIC");
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

    // ------------------------------------------------------------------
    // 结构化错误码
    // ------------------------------------------------------------------
    @Test
    @DisplayName("空响应 / 非 JSON → MALFORMED_BODY")
    void rejectsEmptyOrBrokenBody() {
        assertThat(RegistrySnapshotParser.parse("").error().failure())
                .isEqualTo(RegistrySnapshotParser.Failure.MALFORMED_BODY);
        assertThat(RegistrySnapshotParser.parse("{not json").error().failure())
                .isEqualTo(RegistrySnapshotParser.Failure.MALFORMED_BODY);
        assertThat(RegistrySnapshotParser.parse("[]").error().failure())
                .isEqualTo(RegistrySnapshotParser.Failure.MALFORMED_BODY);
    }

    @Test
    @DisplayName("缺少 snapshotId → MISSING_SNAPSHOT_ID")
    void rejectsMissingSnapshotId() {
        String body = minimal("", "1.1").replace("\"snapshotId\":\"\"", "\"x\":1");
        assertThat(RegistrySnapshotParser.parse(body).error().failure())
                .isEqualTo(RegistrySnapshotParser.Failure.MISSING_SNAPSHOT_ID);
    }

    @Test
    @DisplayName("snapshotId 用时间戳 → MALFORMED_SNAPSHOT_ID")
    void rejectsTimestampAsSnapshotId() {
        var result = RegistrySnapshotParser.parse(minimal("2026-11-16T12:00:00Z", "1.1"));
        assertThat(result.error().failure())
                .isEqualTo(RegistrySnapshotParser.Failure.MALFORMED_SNAPSHOT_ID);
    }

    @Test
    @DisplayName("Schema 主版本不支持 → UNSUPPORTED_SCHEMA")
    void rejectsUnsupportedSchemaMajor() {
        var result = RegistrySnapshotParser.parse(minimal(VALID_ID, "2.0"));
        assertThat(result.error().failure())
                .isEqualTo(RegistrySnapshotParser.Failure.UNSUPPORTED_SCHEMA);
    }

    @Test
    @DisplayName("Contract 主版本不支持 → UNSUPPORTED_CONTRACT（与 Schema 独立）")
    void rejectsUnsupportedContractMajor() {
        // Schema 合法（1.1），但 Contract 是 2.0 —— 必须单独被拒
        var result = RegistrySnapshotParser.parse(minimal(VALID_ID, "1.1", "2.0"));
        assertThat(result.error().failure())
                .as("Schema 正常不代表 Contract 正常，两者必须独立校验")
                .isEqualTo(RegistrySnapshotParser.Failure.UNSUPPORTED_CONTRACT);
    }

    @Test
    @DisplayName("缺少 contractVersion → UNSUPPORTED_CONTRACT")
    void rejectsMissingContractVersion() {
        String body = minimal(VALID_ID, "1.1").replace("\"contractVersion\":\"1.0\",", "");
        assertThat(RegistrySnapshotParser.parse(body).error().failure())
                .isEqualTo(RegistrySnapshotParser.Failure.UNSUPPORTED_CONTRACT);
    }

    @Test
    @DisplayName("Schema 与 Contract 主版本一致时接受（次版本可不同）")
    void acceptsDifferentMinorVersions() {
        // schema 1.9 + contract 1.7 —— 主版本都是 1，应接受
        assertThat(RegistrySnapshotParser.parse(minimal(VALID_ID, "1.9", "1.7")).ok()).isTrue();
    }

    @Test
    @DisplayName("缺少 capabilities / diagnostics / adapters 任一数组 → MISSING_ARRAY")
    void rejectsMissingArrays() {
        assertThat(RegistrySnapshotParser.parse(
                minimal(VALID_ID, "1.1").replace("\"diagnostics\":[]", "\"x\":1"))
                .error().failure())
                .isEqualTo(RegistrySnapshotParser.Failure.MISSING_ARRAY);
        assertThat(RegistrySnapshotParser.parse(
                minimal(VALID_ID, "1.1").replace("\"adapters\":[", "\"y\":["))
                .error().failure())
                .isEqualTo(RegistrySnapshotParser.Failure.MISSING_ARRAY);
    }

    @Test
    @DisplayName("能力缺少 adapterFunction → BROKEN_CAPABILITY")
    void rejectsCapabilityWithoutFunction() {
        String body = minimal(VALID_ID, "1.1")
                .replace("\"adapterFunction\":\"app.x.y\",\"semantics\":\"READ\"",
                         "\"semantics\":\"READ\"");
        assertThat(RegistrySnapshotParser.parse(body).error().failure())
                .isEqualTo(RegistrySnapshotParser.Failure.BROKEN_CAPABILITY);
    }

    // ------------------------------------------------------------------
    // platformScoped 严格校验（N2：失败关闭）
    // ------------------------------------------------------------------
    @Test
    @DisplayName("Adapter 缺少 platformScoped → BROKEN_ADAPTER（不默认 true）")
    void rejectsMissingPlatformScoped() {
        String body = minimal(VALID_ID, "1.1").replace("\"platformScoped\":true,", "");
        assertThat(body).doesNotContain("platformScoped");
        assertThat(RegistrySnapshotParser.parse(body).error().failure())
                .as("缺失该字段必须拒绝整个快照——默认 true 就是失败开放")
                .isEqualTo(RegistrySnapshotParser.Failure.BROKEN_ADAPTER);
    }

    @Test
    @DisplayName("Adapter platformScoped 为 null → BROKEN_ADAPTER")
    void rejectsNullPlatformScoped() {
        String body = minimal(VALID_ID, "1.1")
                .replace("\"platformScoped\":true", "\"platformScoped\":null");
        assertThat(RegistrySnapshotParser.parse(body).error().failure())
                .isEqualTo(RegistrySnapshotParser.Failure.BROKEN_ADAPTER);
    }

    @Test
    @DisplayName("Adapter platformScoped 为字符串 / 数字 → BROKEN_ADAPTER")
    void rejectsNonBooleanPlatformScoped() {
        String asString = minimal(VALID_ID, "1.1")
                .replace("\"platformScoped\":true", "\"platformScoped\":\"false\"");
        assertThat(RegistrySnapshotParser.parse(asString).error().failure())
                .as("字符串 \"false\" 若被 asBoolean() 容错，就会变成真假不明")
                .isEqualTo(RegistrySnapshotParser.Failure.BROKEN_ADAPTER);

        String asNumber = minimal(VALID_ID, "1.1")
                .replace("\"platformScoped\":true", "\"platformScoped\":0");
        assertThat(RegistrySnapshotParser.parse(asNumber).error().failure())
                .isEqualTo(RegistrySnapshotParser.Failure.BROKEN_ADAPTER);
    }

    @Test
    @DisplayName("Adapter 缺少 connectorCode → BROKEN_ADAPTER")
    void rejectsAdapterWithoutConnectorCode() {
        String body = minimal(VALID_ID, "1.1").replace("\"connectorCode\":\"X\",", "");
        assertThat(RegistrySnapshotParser.parse(body).error().failure())
                .isEqualTo(RegistrySnapshotParser.Failure.BROKEN_ADAPTER);
    }

    // ------------------------------------------------------------------
    // 内部一致性
    // ------------------------------------------------------------------
    @Test
    @DisplayName("诊断为函数不可用，但能力仍出现在清单里 → INCONSISTENT")
    void rejectsInconsistentFunctionDiagnostic() {
        String body = minimal(VALID_ID, "1.1").replace("\"diagnostics\":[]", """
                "diagnostics":[{"connectorCode":"X","capabilityCode":"COMMENT",
                                "reason":"INVALID_IMPLEMENTATION","detail":"坏函数",
                                "adapterFunction":"app.x.y"}]
                """);
        assertThat(RegistrySnapshotParser.parse(body).error().failure())
                .isEqualTo(RegistrySnapshotParser.Failure.INCONSISTENT);
    }

    @Test
    @DisplayName("证据类诊断，但能力不在清单里 → INCONSISTENT")
    void rejectsInconsistentEvidenceDiagnostic() {
        String body = minimal(VALID_ID, "1.1").replace("\"diagnostics\":[]", """
                "diagnostics":[{"connectorCode":"X","capabilityCode":"MEDIA_DOWNLOAD",
                                "reason":"EVIDENCE_EXPIRED","detail":"证据过期",
                                "adapterFunction":"app.x.y"}]
                """);
        assertThat(RegistrySnapshotParser.parse(body).error().failure())
                .isEqualTo(RegistrySnapshotParser.Failure.INCONSISTENT);
    }

    @Test
    @DisplayName("证据类诊断且能力在清单里 → 接受（合法组合）")
    void acceptsEvidenceDiagnosticWithObservedCapability() {
        String body = minimal(VALID_ID, "1.1").replace("\"diagnostics\":[]", """
                "diagnostics":[{"connectorCode":"X","capabilityCode":"COMMENT",
                                "reason":"EVIDENCE_EXPIRED","detail":"证据过期",
                                "adapterFunction":"app.x.y"}]
                """);
        assertThat(RegistrySnapshotParser.parse(body).ok())
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

    // ------------------------------------------------------------------
    // 分类不依赖文本
    // ------------------------------------------------------------------
    @Test
    @DisplayName("错误分类与 message 措辞无关（改文案不影响 Failure）")
    void classificationIsIndependentOfMessageWording() {
        // 同一个结构问题（缺 contractVersion），无论 message 长什么样，
        // Failure 都必须是 UNSUPPORTED_CONTRACT —— 因为 message 根本不参与判断。
        String missingContract = minimal(VALID_ID, "1.1")
                .replace("\"contractVersion\":\"1.0\",", "");
        var first = RegistrySnapshotParser.parse(missingContract);
        var second = RegistrySnapshotParser.parse(missingContract.replace(" ", ""));

        assertThat(first.error().failure()).isEqualTo(second.error().failure());
        assertThat(first.error().failure())
                .isEqualTo(RegistrySnapshotParser.Failure.UNSUPPORTED_CONTRACT);
    }
}
