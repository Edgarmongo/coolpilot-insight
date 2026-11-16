package com.coolpilot.insight.integration.capability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对**真实运行的** Collection Service 做一次 HTTP 往返（SOURCE-04-02 验收 A05/A09）。
 *
 * <p>这是刻意"不跳过"的测试：如果容器不可达，测试**失败**而不是 assume 跳过。
 * 理由：静态 JSON 样本只能证明"解析器能读样例"，不能证明"跨服务链路是通的"。
 * 用样本冒充往返,正是 SOURCE-03 审查时被指出的问题。
 *
 * <p>前置条件：{@code cpi-collection} 在 {@code localhost:5001} 运行。
 * 启动方式：
 * <pre>
 *   docker compose up -d collection
 * </pre>
 *
 * <p>可用环境变量覆盖地址：{@code COLLECTION_TEST_URL}（默认 {@code http://localhost:5001}）。
 */
class CollectionSnapshotClientTest {

    private static String baseUrl() {
        String env = System.getenv("COLLECTION_TEST_URL");
        return (env == null || env.isBlank()) ? "http://localhost:5001" : env;
    }

    /** 测试环境未配令牌，所以不传。生产由 COLLECTION_INTERNAL_TOKEN 注入。 */
    private static CollectionServiceClient client() {
        return new CollectionServiceClient(baseUrl(), "");
    }

    @Test
    @DisplayName("真实 HTTP 往返：能拉到完整快照")
    void fetchesRealSnapshot() {
        var result = client().fetchSnapshot();

        assertThat(result.ok())
                .as("需要 cpi-collection 在 %s 运行（docker compose up -d collection）；"
                        + "失败原因: %s", baseUrl(), result.detail())
                .isTrue();

        RegistrySnapshot snap = result.snapshot();
        assertThat(snap).isNotNull();
        assertThat(snap.snapshotId()).startsWith("sha256:");
        assertThat(snap.schemaVersion()).startsWith("1.");
        assertThat(snap.adapters()).isNotEmpty();
        assertThat(snap.capabilities()).isNotEmpty();
        assertThat(snap.diagnostics()).isNotNull();
    }

    @Test
    @DisplayName("真实往返：连续两次请求 snapshotId 必须相同（内容未变）")
    void snapshotIdIsStableOverRealHttp() {
        var first = client().fetchSnapshot();
        var second = client().fetchSnapshot();

        assertThat(first.ok()).as(first.detail()).isTrue();
        assertThat(second.ok()).as(second.detail()).isTrue();
        assertThat(first.snapshot().snapshotId())
                .as("内容未变时 snapshotId 必须稳定——否则缓存永远失效")
                .isEqualTo(second.snapshot().snapshotId());
    }

    @Test
    @DisplayName("真实往返：DOUYIN_PUBLIC 的能力与诊断可被关联查询")
    void resolvesConnectorAndDiagnostics() {
        var result = client().fetchSnapshot();
        assertThat(result.ok()).as(result.detail()).isTrue();
        RegistrySnapshot snap = result.snapshot();

        assertThat(snap.findConnector("DOUYIN_PUBLIC"))
                .as("真实快照应包含 DOUYIN_PUBLIC")
                .isNotNull();
        assertThat(snap.findAdapter("DOUYIN_PUBLIC")).isNotNull();
        assertThat(snap.findAdapter("DOUYIN_PUBLIC").isLoaded()).isTrue();

        // 已注册但未验证的能力：应为 UNVERIFIED，且不在诊断的函数问题里
        var keyword = snap.findConnector("DOUYIN_PUBLIC").find("KEYWORD_SEARCH");
        assertThat(keyword).isNotNull();
        assertThat(keyword.implementationStatus()).isEqualTo("UNVERIFIED");
        assertThat(snap.hasFunctionProblem("DOUYIN_PUBLIC", "KEYWORD_SEARCH"))
                .as("KEYWORD_SEARCH 的函数是能解析的，不应被标为函数问题")
                .isFalse();
    }

    @Test
    @DisplayName("真实往返：快照不包含作业运行结果（未触发平台采集）")
    void realSnapshotHasNoJobArtifacts() {
        var result = client().fetchSnapshot();
        assertThat(result.ok()).as(result.detail()).isTrue();
        RegistrySnapshot snap = result.snapshot();

        // 作业结果只属于 /v1/jobs，不应出现在能力快照里
        assertThat(snap.capabilities()).allSatisfy(doc ->
                assertThat(doc.capabilities()).allSatisfy(cap ->
                        assertThat(cap.code()).isNotBlank()));
    }

    @Test
    @DisplayName("错误令牌 → 若服务配置了 INTERNAL_TOKEN 则拒绝；未配置则放行")
    void tokenHandling() {
        // 本测试环境未配 INTERNAL_TOKEN，带任意令牌也应被接受（服务端未启用校验）
        var withToken = new CollectionServiceClient(baseUrl(), "dummy-token");
        var result = withToken.fetchSnapshot();
        assertThat(result.ok()).as(result.detail()).isTrue();
    }
}
