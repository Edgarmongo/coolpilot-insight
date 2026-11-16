package com.coolpilot.insight.integration.collection;

import com.coolpilot.insight.integration.common.CommandResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link HttpCollectionClient} 的行为测试（SOURCE-06 · A9）。
 *
 * <p>用本地 HTTP server 精确控制每一条分支——因为这里要证明的是
 * **失败原因不会糊成一句"采集失败"**：
 * 401/403/404/409、作业 FAILED、作业超时，五种情况的处置完全不同。
 *
 * <p>真实容器往返另见 {@code tools/verify_job_e2e.sh}（需要 Chrome，
 * 不适合放在单元测试里）。
 */
class HttpCollectionClientTest {

    /** 记录收到的请求体与令牌，供断言。 */
    private static final class Recorder {
        final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
        final AtomicReference<String> token = new AtomicReference<>();
        final AtomicInteger pollHits = new AtomicInteger();
        final AtomicInteger cancelHits = new AtomicInteger();
    }

    private static HttpServer start(Recorder rec, int submitStatus, String submitBody,
                                    String[] statusBodies) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/jobs", exchange -> {
            String path = exchange.getRequestURI().getPath();
            rec.token.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));

            if (path.endsWith("/cancel")) {
                rec.cancelHits.incrementAndGet();
                respond(exchange, 200, "{\"status\":\"CANCELLED\"}");
                return;
            }
            if (path.equals("/v1/jobs")) {
                rec.bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, submitStatus, submitBody);
                return;
            }
            int i = rec.pollHits.getAndIncrement();
            respond(exchange, 200, statusBodies[Math.min(i, statusBodies.length - 1)]);
        });
        server.start();
        return server;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int code, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("成功：提交 → 轮询到 SUCCEEDED，令牌与派发字段都带上")
    void successPath() throws IOException {
        Recorder rec = new Recorder();
        HttpServer server = start(rec, 202, "{\"jobId\":\"task-1-item-1\",\"status\":\"ACCEPTED\"}",
                new String[]{
                        "{\"status\":\"RUNNING\",\"collected\":0,\"pages\":0}",
                        "{\"status\":\"SUCCEEDED\",\"collected\":5,\"pages\":2,\"logTail\":[\"浏览器启动成功\"]}"
                });
        try {
            HttpCollectionClient client = new HttpCollectionClient(
                    url(server), "tok-123", "DOUYIN_PUBLIC", null, 5);

            CommandResult r = client.collectComments("7703721523349239951", 20, false, 1L, 2L);

            assertThat(r.success()).isTrue();
            assertThat(r.output()).contains("SUCCEEDED").contains("collected=5").contains("pages=2");
            // 令牌必须带上，否则服务端 401
            assertThat(rec.token.get()).isEqualTo("tok-123");

            String submitted = rec.bodies.get(0);
            // 派发目标必须显式给出：服务端不接受"自己猜"
            assertThat(submitted).contains("\"connectorCode\":\"DOUYIN_PUBLIC\"");
            assertThat(submitted).contains("\"capabilityCode\":\"COMMENT\"");
            assertThat(submitted).contains("\"accessMode\":\"PUBLIC\"");
            assertThat(submitted).contains("\"jobId\":\"task-1-item-2\"");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("登录态采集：accessMode 必须是 AUTHENTICATED，不能偷偷降级为 PUBLIC")
    void loginModeUsesAuthenticated() throws IOException {
        Recorder rec = new Recorder();
        HttpServer server = start(rec, 202, "{\"jobId\":\"x\",\"status\":\"ACCEPTED\"}",
                new String[]{"{\"status\":\"SUCCEEDED\",\"collected\":1,\"pages\":1}"});
        try {
            new HttpCollectionClient(url(server), "", "DOUYIN_PUBLIC", null, 5)
                    .collectComments("123", 10, true, null, null);

            assertThat(rec.bodies.get(0)).contains("\"accessMode\":\"AUTHENTICATED\"");
            assertThat(rec.bodies.get(0)).contains("\"useLogin\":true");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("409 能力不可执行 → 失败，且把服务端的 code 原样带出来")
    void rejectionKeepsServerCode() throws IOException {
        Recorder rec = new Recorder();
        HttpServer server = start(rec, 409,
                "{\"code\":\"NOT_EXECUTABLE\",\"message\":\"能力 COMMENT 的实现状态是 UNVERIFIED\"}",
                new String[]{"{}"});
        try {
            CommandResult r = new HttpCollectionClient(url(server), "", "DOUYIN_PUBLIC", null, 5)
                    .collectComments("123", 10, false, null, null);

            assertThat(r.success()).isFalse();
            assertThat(r.output())
                    .contains("HTTP 409")
                    .contains("NOT_EXECUTABLE")
                    .contains("UNVERIFIED");
            // 说明一下：被拒时不能轮询——作业压根没建
            assertThat(rec.pollHits.get()).isZero();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("401 令牌错 → 失败，不误报成能力问题")
    void unauthorizedIsNotCapabilityProblem() throws IOException {
        Recorder rec = new Recorder();
        HttpServer server = start(rec, 401, "{\"code\":\"UNAUTHORIZED\"}", new String[]{"{}"});
        try {
            CommandResult r = new HttpCollectionClient(url(server), "", "DOUYIN_PUBLIC", null, 5)
                    .collectComments("123", 10, false, null, null);

            assertThat(r.success()).isFalse();
            assertThat(r.output()).contains("HTTP 401").contains("UNAUTHORIZED");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("作业 FAILED → 失败，并带上作业的日志尾巴")
    void failedJobCarriesLogTail() throws IOException {
        Recorder rec = new Recorder();
        HttpServer server = start(rec, 202, "{}", new String[]{
                "{\"status\":\"FAILED\",\"errorCode\":\"COLLECT_INCOMPLETE\","
                        + "\"errorMessage\":\"浏览器启动失败\",\"logTail\":[\"Preflight 失败\"]}"});
        try {
            CommandResult r = new HttpCollectionClient(url(server), "", "DOUYIN_PUBLIC", null, 5)
                    .collectComments("123", 10, false, null, null);

            assertThat(r.success()).isFalse();
            assertThat(r.output()).contains("COLLECT_INCOMPLETE").contains("Preflight 失败");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("PARTIAL（一个数据包都没收到）→ 失败，不当成完成")
    void partialIsFailure() throws IOException {
        Recorder rec = new Recorder();
        HttpServer server = start(rec, 202, "{}", new String[]{
                "{\"status\":\"PARTIAL\",\"errorCode\":\"NO_DATA\","
                        + "\"errorMessage\":\"本次没有采到任何评论（finishedReason=NO_RESPONSE）\"}"});
        try {
            CommandResult r = new HttpCollectionClient(url(server), "", "DOUYIN_PUBLIC", null, 5)
                    .collectComments("123", 10, false, null, null);

            assertThat(r.success()).isFalse();
            assertThat(r.output()).contains("PARTIAL").contains("NO_RESPONSE");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("超时 → 失败并尽力取消，不谎报成功")
    void timeoutCancels() throws IOException {
        Recorder rec = new Recorder();
        HttpServer server = start(rec, 202, "{}",
                new String[]{"{\"status\":\"RUNNING\",\"collected\":0}"});
        try {
            // 超时 1 秒 + 轮询 5ms：把 10 分钟的分支压到可测的时间尺度
            HttpCollectionClient client = new HttpCollectionClient(
                    url(server), "", "DOUYIN_PUBLIC", null, 5, 1, 1);

            CommandResult r = client.collectComments("123", 10, false, 1L, 1L);

            assertThat(r.success()).isFalse();
            assertThat(r.output()).contains("超时");
            assertThat(rec.pollHits.get()).isGreaterThan(0);
            assertThat(rec.cancelHits.get()).isGreaterThan(0);
        } finally {
            server.stop(0);
        }
    }
}
