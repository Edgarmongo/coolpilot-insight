package com.coolpilot.insight.source;

import com.coolpilot.insight.AbstractIntegrationTest;
import com.coolpilot.insight.integration.health.ServiceHealthProbe;
import com.coolpilot.insight.source.application.AccountVerificationService;
import com.coolpilot.insight.source.application.SourceAccountService;
import com.coolpilot.insight.source.domain.SourceAccount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 账号验证对「服务可达性」的判断。
 *
 * <p>要证明的核心命题：**AI 服务与采集服务是两个独立的服务，
 * 它们的健康状态不能相互替代**。
 *
 * <p>历史缺陷：验证逻辑把 ai-service 的 {@code /health} 当成采集服务的可达性。
 * 二者都返回 200，于是错误长期未暴露——直到采集服务被拆成独立容器。
 *
 * <p>本类用两个本地 HTTP server 精确控制两个服务的可达性组合。
 */
@TestPropertySource(properties = {
        "app.secret-dir=target/test-secrets",
        "app.collection-service-url=http://127.0.0.1:15081/collection",
        "app.ai-service-url=http://127.0.0.1:15082/ai"
})
class AccountVerificationProbeTest extends AbstractIntegrationTest {

    /** 采集服务的健康开关。 */
    static final AtomicBoolean COLLECTION_UP = new AtomicBoolean(true);
    /** AI 服务的健康开关。 */
    static final AtomicBoolean AI_UP = new AtomicBoolean(true);

    static {
        try {
            Files.createDirectories(Paths.get("target/test-secrets/account"));
        } catch (IOException e) {
            throw new IllegalStateException("无法创建测试凭证目录", e);
        }
        try {
            // 每个 server 只注册自己的路径：打错服务会拿到 404 → 判为不可达
            startServer(15081, "/collection/health", COLLECTION_UP);
            startServer(15082, "/ai/health", AI_UP);
        } catch (IOException e) {
            throw new IllegalStateException("无法启动测试用健康检查服务", e);
        }
    }

    private static void startServer(int port, String path, AtomicBoolean up) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext(path, exchange -> {
            int code = up.get() ? 200 : 503;
            byte[] body = ("{\"up\":" + up.get() + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.setExecutor(null);
        server.start();
    }

    @Autowired private SourceAccountService accountService;
    @Autowired private AccountVerificationService verificationService;

    private SourceAccount seedWithCredential(String ext) {
        Path f = Paths.get("target/test-secrets/account", ext + ".txt");
        try {
            Files.writeString(f, "fake-cookie");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        SourceAccount a = new SourceAccount();
        a.setSourceId(1L);
        a.setAccountName("探活测试" + ext);
        a.setExternalAccountId(ext);
        a.setAuthType("COOKIE");
        a.setCredentialRef("local-secret:account/" + ext + ".txt");
        a.setExpiresAt(LocalDateTime.now().plusDays(30));
        return accountService.create(a);
    }

    @Test
    @DisplayName("采集服务不可达 → SERVICE_UNAVAILABLE，且不得改写 auth_status")
    void collectionDownKeepsAuthStatus() {
        SourceAccount a = seedWithCredential("p-1");
        COLLECTION_UP.set(false);
        try {
            var r = verificationService.verify(a.getId());

            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo("SERVICE_UNAVAILABLE");
            assertThat(r.authStatus()).isEqualTo(a.getAuthStatus());
            assertThat(accountService.detail(a.getId()).getAuthStatus())
                    .as("服务不可达属于环境问题，不能改写账号状态")
                    .isEqualTo(a.getAuthStatus());
        } finally {
            COLLECTION_UP.set(true);
        }
    }

    @Test
    @DisplayName("采集服务可达但 AI 服务不可达 → 账号仍 VALID，两者互不替代")
    void aiDownDoesNotBlockValid() {
        SourceAccount a = seedWithCredential("p-2");
        AI_UP.set(false);
        try {
            var r = verificationService.verify(a.getId());

            assertThat(r.success()).isTrue();
            assertThat(r.authStatus()).isEqualTo("VALID");
            assertThat(r.serviceChecks().get(AccountVerificationService.SERVICE_COLLECTION).reachable())
                    .isTrue();
            assertThat(r.serviceChecks().get(AccountVerificationService.SERVICE_AI).reachable())
                    .as("AI 服务不可达必须被如实报告")
                    .isFalse();
        } finally {
            AI_UP.set(true);
        }
    }

    @Test
    @DisplayName("两个服务的结果分别出现在 serviceChecks 里，互不覆盖")
    void bothServiceResultsReported() {
        SourceAccount a = seedWithCredential("p-3");
        COLLECTION_UP.set(false);
        AI_UP.set(false);
        try {
            var r = verificationService.verify(a.getId());

            assertThat(r.serviceChecks()).containsKeys(
                    AccountVerificationService.SERVICE_COLLECTION,
                    AccountVerificationService.SERVICE_AI);
            assertThat(r.serviceChecks().get(AccountVerificationService.SERVICE_COLLECTION).reachable())
                    .isFalse();
            assertThat(r.serviceChecks().get(AccountVerificationService.SERVICE_AI).reachable())
                    .isFalse();
        } finally {
            COLLECTION_UP.set(true);
            AI_UP.set(true);
        }
    }

    @Test
    @DisplayName("探活打的是各自的目标服务，不能串台")
    void probesTargetDistinctServices() {
        ServiceHealthProbe collection = new ServiceHealthProbe("COLLECTION_SERVICE",
                "http://127.0.0.1:15081/collection");
        ServiceHealthProbe ai = new ServiceHealthProbe("AI_SERVICE",
                "http://127.0.0.1:15082/ai");

        assertThat(collection.probe().reachable()).isTrue();
        assertThat(ai.probe().reachable()).isTrue();
        assertThat(collection.probe().baseUrl()).isNotEqualTo(ai.probe().baseUrl());
        assertThat(collection.probe().serviceName()).isNotEqualTo(ai.probe().serviceName());
    }

    @Test
    @DisplayName("未配置地址 → 判为不可达，不抛异常")
    void blankUrlIsUnreachable() {
        ServiceHealthProbe probe = new ServiceHealthProbe("X", "");
        var r = probe.probe();
        assertThat(r.reachable()).isFalse();
        assertThat(r.detail()).contains("未配置");
    }
}
