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
                               "capabilities":[{"code":"COMMENT","category":"COMMENT",
                                     "accessModes":["PUBLIC"],"implementationStatus":"UNVERIFIED",
                                     "contractVersion":"1.0",
                                     "adapterFunction":"app.x.y","semantics":"READ"}]}],
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
    // O1：adapters[].capabilities 与 capabilities[] 必须互为镜像
    //
    // 二者由 Python 的同一次 evaluate_all() 生成，是同一份声明的两个视图。
    // Java 同时消费：候选码取自 adapters，状态取自 documents。
    // 分叉会出现"用 A 的清单 + B 的状态"这种无人定义过的组合，故拒绝整份快照。
    // ------------------------------------------------------------------
    /** 只替换 adapters 段（文档段之前）。 */
    private static String inAdapter(String body, String from, String to) {
        int i = body.indexOf("\"capabilities\":[{\"schemaVersion\"");
        return body.substring(0, i).replace(from, to) + body.substring(i);
    }

    /** 只替换 capabilities 文档段。 */
    private static String inDoc(String body, String from, String to) {
        int i = body.indexOf("\"capabilities\":[{\"schemaVersion\"");
        return body.substring(0, i) + body.substring(i).replace(from, to);
    }

    @Test
    @DisplayName("O1 两份副本的实现状态不一致 → BROKEN_CAPABILITY（报出两个值）")
    void rejectsMirrorStatusMismatch() {
        String body = inDoc(minimal(VALID_ID, "1.1"),
                "\"implementationStatus\":\"UNVERIFIED\"", "\"implementationStatus\":\"VERIFIED\"");

        var result = RegistrySnapshotParser.parse(body);
        assertThat(result.ok()).isFalse();
        assertThat(result.error().failure()).isEqualTo(RegistrySnapshotParser.Failure.BROKEN_CAPABILITY);
        assertThat(result.error().message())
                .contains("X/COMMENT").contains("adapters=UNVERIFIED").contains("capabilities=VERIFIED");
    }

    @Test
    @DisplayName("O1 能力只出现在 adapters 侧 → BROKEN_CAPABILITY")
    void rejectsAdapterOnlyCapability() {
        String body = inAdapter(minimal(VALID_ID, "1.1"),
                "\"semantics\":\"READ\"}]}],",
                "\"semantics\":\"READ\"},{\"code\":\"EXTRA\",\"category\":\"X\","
                        + "\"accessModes\":[\"PUBLIC\"],\"implementationStatus\":\"VERIFIED\","
                        + "\"contractVersion\":\"1.0\",\"adapterFunction\":\"app.x.z\","
                        + "\"semantics\":\"READ\"}]}],");

        var result = RegistrySnapshotParser.parse(body);
        assertThat(result.ok()).isFalse();
        assertThat(result.error().message()).contains("EXTRA").contains("不在 capabilities[]");
    }

    @Test
    @DisplayName("O1 能力只出现在 capabilities 文档侧 → BROKEN_CAPABILITY")
    void rejectsDocumentOnlyCapability() {
        String body = inDoc(minimal(VALID_ID, "1.1"),
                "\"semantics\":\"READ\"}]}]",
                "\"semantics\":\"READ\"},{\"code\":\"EXTRA\",\"category\":\"X\","
                        + "\"accessModes\":[\"PUBLIC\"],\"implementationStatus\":\"VERIFIED\","
                        + "\"contractVersion\":\"1.0\",\"adapterFunction\":\"app.x.z\","
                        + "\"semantics\":\"READ\"}]}]");

        var result = RegistrySnapshotParser.parse(body);
        assertThat(result.ok()).isFalse();
        assertThat(result.error().message()).contains("EXTRA").contains("不在 adapters");
    }

    @Test
    @DisplayName("M1 evidence 不一致 → BROKEN_CAPABILITY（状态相同也不放过）")
    void rejectsMirrorEvidenceMismatch() {
        // 两份能力码与 implementationStatus 完全一致，只有 evidence 不同：
        // evidence.accepted 决定第 ⑮~⑰ 步，是判定输入，不能被镜像校验忽略
        String body = inDoc(minimal(VALID_ID, "1.1"),
                "\"semantics\":\"READ\"}]}],",
                "\"semantics\":\"READ\",\"evidence\":{\"accepted\":true,"
                        + "\"verifiedAt\":\"2026-11-16T00:00:00Z\","
                        + "\"method\":\"REAL_PLATFORM_RUN\",\"contentHash\":\"h\"}}]}],");

        var result = RegistrySnapshotParser.parse(body);
        assertThat(result.ok()).isFalse();
        assertThat(result.error().failure()).isEqualTo(RegistrySnapshotParser.Failure.BROKEN_CAPABILITY);
        assertThat(result.error().message())
                .contains("X/COMMENT").contains("evidence")
                .contains("<none>").contains("accepted=true");
    }

    @Test
    @DisplayName("M1 accessModes 不一致 → BROKEN_CAPABILITY（决定第 ⑭ 步）")
    void rejectsMirrorAccessModesMismatch() {
        String body = inDoc(minimal(VALID_ID, "1.1"),
                "\"accessModes\":[\"PUBLIC\"]",
                "\"accessModes\":[\"PUBLIC\",\"AUTHENTICATED\"]");

        var result = RegistrySnapshotParser.parse(body);
        assertThat(result.ok()).isFalse();
        assertThat(result.error().message())
                .contains("X/COMMENT").contains("accessModes").contains("AUTHENTICATED");
    }

    @Test
    @DisplayName("M1 accessModes 顺序不同但元素相同 → 接受（集合语义）")
    void acceptsDifferentAccessModesOrder() {
        String body = inAdapter(minimal(VALID_ID, "1.1"),
                "\"accessModes\":[\"PUBLIC\"]",
                "\"accessModes\":[\"PUBLIC\"]");
        // 两侧同为 PUBLIC：基线
        assertThat(RegistrySnapshotParser.parse(body).ok()).isTrue();

        // 两侧都是 PUBLIC+AUTHENTICATED，只是顺序相反
        String both = inAdapter(minimal(VALID_ID, "1.1"),
                "\"accessModes\":[\"PUBLIC\"]",
                "\"accessModes\":[\"AUTHENTICATED\",\"PUBLIC\"]");
        both = inDoc(both, "\"accessModes\":[\"PUBLIC\"]",
                "\"accessModes\":[\"PUBLIC\",\"AUTHENTICATED\"]");
        assertThat(RegistrySnapshotParser.parse(both).ok())
                .as("accessModes 是集合，顺序不同不应判为镜像不一致")
                .isTrue();
    }

    @Test
    @DisplayName("M1 capabilities 中重复 connectorCode → BROKEN_CAPABILITY")
    void rejectsDuplicateConnectorDocument() {
        // findConnector() 取第一条文档，镜像索引却是后写覆盖；
        // 重复时两者看的不是同一份，必须直接拒绝
        String dupDoc = "{\"schemaVersion\":\"1.1\",\"sourceCode\":\"DOUYIN\","
                + "\"connectorCode\":\"X\",\"connectorVersion\":\"1.0.0\","
                + "\"capabilities\":[{\"code\":\"COMMENT\",\"category\":\"COMMENT\","
                + "\"accessModes\":[\"PUBLIC\"],\"implementationStatus\":\"UNVERIFIED\","
                + "\"contractVersion\":\"1.0\",\"adapterFunction\":\"app.x.y\","
                + "\"semantics\":\"READ\"}]}";

        // 原文档尾部是 "semantics":"READ"}]}],
        // 拆成：闭合本条 → 逗号 → 第二份文档 → 闭合数组
        String body = inDoc(minimal(VALID_ID, "1.1"),
                "\"semantics\":\"READ\"}]}],",
                "\"semantics\":\"READ\"}]}," + dupDoc + "],");

        var result = RegistrySnapshotParser.parse(body);
        assertThat(result.ok())
                .as("构造的 JSON 本身必须合法，否则测不到重复逻辑")
                .isFalse();
        assertThat(result.error().message()).contains("重复的 connectorCode");
    }

    @Test
    @DisplayName("O1 STUB（platformScoped=false）的能力不参与镜像比较")
    void stubCapabilitiesExcludedFromMirror() {
        // STUB 在文档侧本来就没有条目，把它的能力拿来比较会导致"正常的快照被判非法"
        String body = minimal(VALID_ID, "1.1").replace("\"adapters\":[{\"connectorCode\":\"X\",",
                "\"adapters\":[{\"connectorCode\":\"STUB_PUBLIC\",\"sourceCode\":\"STUB\","
                        + "\"connectorVersion\":\"1.0.0\",\"loadStatus\":\"LOADED\","
                        + "\"module\":\"app.stub\",\"platformScoped\":false,\"capabilityCount\":1,"
                        + "\"capabilities\":[{\"code\":\"SOME_STUB_CAP\",\"category\":\"X\","
                        + "\"accessModes\":[\"PUBLIC\"],\"implementationStatus\":\"DEGRADED\","
                        + "\"contractVersion\":\"1.0\",\"adapterFunction\":\"app.stub.x\","
                        + "\"semantics\":\"READ\"}]},"
                        + "{\"connectorCode\":\"X\",");

        var result = RegistrySnapshotParser.parse(body);
        assertThat(result.ok())
                .as("STUB 不进文档侧，不应因镜像校验被误判：%s",
                        result.ok() ? "" : result.error().message())
                .isTrue();
    }

    // ------------------------------------------------------------------
    // implementationStatus 六值严格校验（独立复核 F1-2）
    //
    // 跨语言枚举契约：只读不验会让 Python 的拼写错误/版本不兼容值被当成正常状态，
    // 最终可能落到 AVAILABLE。这里锁定"缺失/null/非法一律拒绝整份快照"。
    // ------------------------------------------------------------------
    @Test
    @DisplayName("implementationStatus 非法值 → BROKEN_CAPABILITY（不映射为默认合法状态）")
    void rejectsUnknownImplementationStatus() {
        String body = minimal(VALID_ID, "1.1")
                .replace("\"implementationStatus\":\"UNVERIFIED\"",
                        "\"implementationStatus\":\"PARTIAL\"");
        assertThat(body).contains("PARTIAL");

        var result = RegistrySnapshotParser.parse(body);
        assertThat(result.ok()).isFalse();
        assertThat(result.error().failure()).isEqualTo(RegistrySnapshotParser.Failure.BROKEN_CAPABILITY);
        assertThat(result.error().message())
                .as("必须能诊断到是哪条能力、什么值、有哪些合法值")
                .contains("PARTIAL").contains("implementationStatus").contains("VERIFIED");
    }

    @Test
    @DisplayName("implementationStatus 缺失 / null → BROKEN_CAPABILITY")
    void rejectsMissingImplementationStatus() {
        // 缺失
        String missing = minimal(VALID_ID, "1.1")
                .replace("\"implementationStatus\":\"UNVERIFIED\",", "");
        assertThat(missing).doesNotContain("implementationStatus");
        assertThat(RegistrySnapshotParser.parse(missing).error().failure())
                .isEqualTo(RegistrySnapshotParser.Failure.BROKEN_CAPABILITY);

        // null
        String nullVal = minimal(VALID_ID, "1.1")
                .replace("\"implementationStatus\":\"UNVERIFIED\"",
                        "\"implementationStatus\":null");
        var result = RegistrySnapshotParser.parse(nullVal);
        assertThat(result.error().failure()).isEqualTo(RegistrySnapshotParser.Failure.BROKEN_CAPABILITY);
        assertThat(result.error().message()).contains("缺失或 null");
    }

    @Test
    @DisplayName("implementationStatus 大小写敏感：小写 verified 也拒绝")
    void rejectsWrongCaseImplementationStatus() {
        String body = minimal(VALID_ID, "1.1")
                .replace("\"implementationStatus\":\"UNVERIFIED\"",
                        "\"implementationStatus\":\"verified\"");
        assertThat(RegistrySnapshotParser.parse(body).error().failure())
                .as("枚举是精确匹配，不做大小写归一")
                .isEqualTo(RegistrySnapshotParser.Failure.BROKEN_CAPABILITY);
    }

    @Test
    @DisplayName("六值全部合法：逐值解析均通过")
    void acceptsAllSixImplementationStatuses() {
        for (String st : new String[]{"UNVERIFIED", "IMPLEMENTED", "VERIFIED",
                "DEGRADED", "DISABLED", "UNSUPPORTED"}) {
            String body = minimal(VALID_ID, "1.1")
                    .replace("\"implementationStatus\":\"UNVERIFIED\"",
                            "\"implementationStatus\":\"" + st + "\"");
            var result = RegistrySnapshotParser.parse(body);
            assertThat(result.ok())
                    .as("%s 必须被接受，失败原因=%s", st,
                            result.ok() ? "" : result.error().message())
                    .isTrue();
        }
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
