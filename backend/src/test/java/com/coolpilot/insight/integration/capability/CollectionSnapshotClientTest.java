package com.coolpilot.insight.integration.capability;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 快照客户端的 HTTP 行为测试（SOURCE-04-02 验收 A05/A07/A09）。
 *
 * <p>分两类，**不混用**：
 *
 * <ul>
 *   <li><b>真实往返</b>：直连 {@code cpi-collection} 容器，验证跨服务链路真的通。
 *       容器不可达则测试失败，<b>不 assume 跳过</b>——用静态样本冒充往返，
 *       正是 SOURCE-03 审查被指出的问题。</li>
 *   <li><b>本地 HTTP server</b>：精确控制响应码与请求头，验证令牌传递与错误分类。
 *       这类断言必须是确定性的，不能依赖"服务恰好没配 token"这种环境假设。</li>
 * </ul>
 *
 * <p>前置条件（真实往返部分）：{@code docker compose up -d collection}。
 * 地址可用 {@code COLLECTION_TEST_URL} 覆盖。
 */
class CollectionSnapshotClientTest {

    /**
     * 真实往返用的内部令牌。
     *
     * <p>部署配置了 {@code COLLECTION_INTERNAL_TOKEN} 之后，采集服务的快照端点会拒绝匿名请求，
     * 因此测试也必须带上令牌——这本身是对的：测试要模拟真实调用方，而不是走一条生产上不存在的匿名通道。
     */
    private static String token() {
        String env = System.getenv("COLLECTION_TEST_TOKEN");
        if (env == null || env.isBlank()) {
            env = System.getenv("COLLECTION_INTERNAL_TOKEN");
        }
        return env == null ? "" : env.trim();
    }

    private static String baseUrl() {
        String env = System.getenv("COLLECTION_TEST_URL");
        return (env == null || env.isBlank()) ? "http://localhost:5001" : env;
    }

    private static String sampleBody() throws IOException {
        try (InputStream in = CollectionSnapshotClientTest.class
                .getResourceAsStream("/registry-snapshot-sample.json")) {
            assertThat(in).as("快照样本必须存在").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 起一个本地 server，按给定状态码与响应体回应快照请求，并记录请求头。 */
    private static HttpServer startStub(int status, String body,
                                        AtomicReference<String> tokenHeader,
                                        AtomicInteger hits) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(CollectionServiceClient.SNAPSHOT_PATH, exchange -> {
            tokenHeader.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            hits.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return server;
    }

    // ------------------------------------------------------------------
    // 真实往返（依赖容器）
    // ------------------------------------------------------------------
    @Test
    @DisplayName("真实 HTTP 往返：能拉到完整快照")
    void fetchesRealSnapshot() {
        var result = new CollectionServiceClient(baseUrl(), token()).fetchSnapshot();

        assertThat(result.ok())
                .as("需要 cpi-collection 在 %s 运行（docker compose up -d collection）；"
                        + "失败原因: %s", baseUrl(), result.detail())
                .isTrue();

        RegistrySnapshot snap = result.snapshot();
        assertThat(snap.snapshotId()).startsWith("sha256:");
        assertThat(snap.schemaVersion()).startsWith("1.");
        assertThat(snap.contractVersion()).startsWith("1.");
        assertThat(snap.adapters()).isNotEmpty();
        assertThat(snap.capabilities()).isNotEmpty();
        assertThat(snap.diagnostics()).isNotNull();
    }

    @Test
    @DisplayName("真实往返：连续两次请求 snapshotId 必须相同（内容未变）")
    void snapshotIdIsStableOverRealHttp() {
        var first = new CollectionServiceClient(baseUrl(), token()).fetchSnapshot();
        var second = new CollectionServiceClient(baseUrl(), token()).fetchSnapshot();

        assertThat(first.ok()).as(first.detail()).isTrue();
        assertThat(second.ok()).as(second.detail()).isTrue();
        assertThat(first.snapshot().snapshotId())
                .as("内容未变时 snapshotId 必须稳定——否则缓存永远失效")
                .isEqualTo(second.snapshot().snapshotId());
    }

    @Test
    @DisplayName("真实往返：DOUYIN_PUBLIC 的能力与诊断可被关联查询")
    void resolvesConnectorAndDiagnostics() {
        var result = new CollectionServiceClient(baseUrl(), token()).fetchSnapshot();
        assertThat(result.ok()).as(result.detail()).isTrue();
        RegistrySnapshot snap = result.snapshot();

        assertThat(snap.findConnector("DOUYIN_PUBLIC")).isNotNull();
        assertThat(snap.findAdapter("DOUYIN_PUBLIC")).isNotNull();
        assertThat(snap.findAdapter("DOUYIN_PUBLIC").isLoaded()).isTrue();
        assertThat(snap.findAdapter("DOUYIN_PUBLIC").platformScoped())
                .as("真实平台接入的 platformScoped 必须是 true")
                .isTrue();

        var keyword = snap.findConnector("DOUYIN_PUBLIC").find("KEYWORD_SEARCH");
        assertThat(keyword).isNotNull();
        assertThat(keyword.implementationStatus()).isEqualTo("UNVERIFIED");
        assertThat(snap.hasFunctionProblem("DOUYIN_PUBLIC", "KEYWORD_SEARCH"))
                .as("KEYWORD_SEARCH 的函数是能解析的，不应被标为函数问题")
                .isFalse();
    }

    @Test
    @DisplayName("真实往返：快照里没有作业运行结果（未触发平台采集）")
    void realSnapshotHasNoJobArtifacts() {
        var result = new CollectionServiceClient(baseUrl(), token()).fetchSnapshot();
        assertThat(result.ok()).as(result.detail()).isTrue();

        assertThat(result.snapshot().capabilities()).allSatisfy(doc ->
                assertThat(doc.capabilities()).allSatisfy(cap ->
                        assertThat(cap.code()).isNotBlank()));
    }

    // ------------------------------------------------------------------
    // 令牌传递（确定性，不依赖容器）
    // ------------------------------------------------------------------
    @Test
    @DisplayName("令牌为空时不发送 X-Internal-Token，非空时发送")
    void sendsTokenHeaderOnlyWhenConfigured() throws Exception {
        var seen = new AtomicReference<String>();
        var hits = new AtomicInteger();
        HttpServer server = startStub(200, sampleBody(), seen, hits);
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();

            seen.set("__unset__");
            var without = new CollectionServiceClient(url, "").fetchSnapshot();
            assertThat(without.ok()).as(without.detail()).isTrue();
            assertThat(seen.get())
                    .as("未配置令牌时不应发送该头")
                    .isNull();

            seen.set(null);
            var with = new CollectionServiceClient(url, "secret-token").fetchSnapshot();
            assertThat(with.ok()).as(with.detail()).isTrue();
            assertThat(seen.get()).isEqualTo("secret-token");

            assertThat(hits.get()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------------
    // 错误分类（确定性）
    // ------------------------------------------------------------------
    @Test
    @DisplayName("HTTP 500 → Kind.HTTP_ERROR")
    void httpErrorIsClassified() throws Exception {
        var seen = new AtomicReference<String>();
        var hits = new AtomicInteger();
        HttpServer server = startStub(500, "{}", seen, hits);
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            var result = new CollectionServiceClient(url, "").fetchSnapshot();

            assertThat(result.ok()).isFalse();
            assertThat(result.kind()).isEqualTo(CollectionServiceClient.FetchResult.Kind.HTTP_ERROR);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("响应非法 JSON → Kind.MALFORMED_RESPONSE")
    void malformedBodyIsClassified() throws Exception {
        var seen = new AtomicReference<String>();
        var hits = new AtomicInteger();
        HttpServer server = startStub(200, "{not json", seen, hits);
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            var result = new CollectionServiceClient(url, "").fetchSnapshot();

            assertThat(result.kind())
                    .isEqualTo(CollectionServiceClient.FetchResult.Kind.MALFORMED_RESPONSE);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Contract 主版本不支持 → Kind.CONTRACT_UNSUPPORTED（与 Schema 分开）")
    void contractUnsupportedIsClassified() throws Exception {
        String body = sampleBody().replaceFirst("\"contractVersion\"\\s*:\\s*\"1\\.[0-9]+\"",
                "\"contractVersion\":\"2.0\"");
        var seen = new AtomicReference<String>();
        var hits = new AtomicInteger();
        HttpServer server = startStub(200, body, seen, hits);
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            var result = new CollectionServiceClient(url, "").fetchSnapshot();

            assertThat(result.kind())
                    .as("Schema 合法但 Contract 不兼容，必须单独分类")
                    .isEqualTo(CollectionServiceClient.FetchResult.Kind.CONTRACT_UNSUPPORTED);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Schema 主版本不支持 → Kind.SCHEMA_UNSUPPORTED")
    void schemaUnsupportedIsClassified() throws Exception {
        String body = sampleBody().replaceFirst("\"schemaVersion\"\\s*:\\s*\"1\\.[0-9]+\"",
                "\"schemaVersion\":\"2.0\"");
        var seen = new AtomicReference<String>();
        var hits = new AtomicInteger();
        HttpServer server = startStub(200, body, seen, hits);
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            var result = new CollectionServiceClient(url, "").fetchSnapshot();

            assertThat(result.kind())
                    .isEqualTo(CollectionServiceClient.FetchResult.Kind.SCHEMA_UNSUPPORTED);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("不可达地址 → Kind.UNREACHABLE")
    void unreachableIsClassified() {
        // 端口 1 必然拒绝连接
        var result = new CollectionServiceClient("http://127.0.0.1:1", "").fetchSnapshot();
        assertThat(result.kind())
                .isEqualTo(CollectionServiceClient.FetchResult.Kind.UNREACHABLE);
    }
}
