package com.coolpilot.insight.system.application;

import com.coolpilot.insight.integration.capability.CollectionServiceClient;
import com.coolpilot.insight.integration.capability.SnapshotProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分层健康检查（SOURCE-05）。
 *
 * <h3>为什么不能只看容器 healthy</h3>
 * 容器活着只说明进程没退。真正会坏的是**依赖**：数据库连不上、采集服务的 Registry 挂了、
 * AI 服务起不来。这三种故障的表现完全不同（采集报 500、洞察静默降级、报告生成失败），
 * 但监控面板上都只是"容器 Up"。
 *
 * <p>所以这里按**依赖层**逐个探测，每层给出：状态、耗时、以及"为什么"。
 *
 * <h3>状态语义</h3>
 * <ul>
 *   <li>{@code UP}：探测通过；</li>
 *   <li>{@code DEGRADED}：能连上但不可用（如 Registry 响应 schema 不兼容），或非关键依赖失败；</li>
 *   <li>{@code DOWN}：探测失败，相关功能已不可用。</li>
 * </ul>
 *
 * <p>**AI 服务失败的总体状态是 DEGRADED 而不是 DOWN**：没有 AI 时分析/报告降级，
 * 但采集与浏览仍然可用。把它判成 DOWN 会让人误以为整个平台挂了。
 */
@Service
public class LayeredHealthService {

    private static final Logger log = LoggerFactory.getLogger(LayeredHealthService.class);

    private static final int TIMEOUT_SECONDS = 3;

    private final JdbcTemplate jdbc;
    private final SnapshotProvider snapshotProvider;
    private final String aiServiceUrl;

    public LayeredHealthService(JdbcTemplate jdbc,
                                SnapshotProvider snapshotProvider,
                                @Value("${app.ai-service-url:http://127.0.0.1:5000}") String aiServiceUrl) {
        this.jdbc = jdbc;
        this.snapshotProvider = snapshotProvider;
        this.aiServiceUrl = aiServiceUrl == null ? "" : aiServiceUrl.trim();
    }

    public Map<String, Object> check() {
        List<Map<String, Object>> layers = new ArrayList<>();
        layers.add(checkBackend());
        layers.add(checkDatabase());
        layers.add(checkCollection());
        layers.add(checkAi());

        boolean anyDown = layers.stream().anyMatch(l -> "DOWN".equals(l.get("status"))
                && !"ai".equals(l.get("layer")));
        boolean anyDegraded = layers.stream().anyMatch(l -> !"UP".equals(l.get("status")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", anyDown ? "DOWN" : (anyDegraded ? "DEGRADED" : "UP"));
        out.put("layers", layers);
        out.put("checkedAt", java.time.OffsetDateTime.now().toString());
        return out;
    }

    // ------------------------------------------------------------------
    private Map<String, Object> checkBackend() {
        Map<String, Object> m = layer("backend", "后端进程");
        long start = System.currentTimeMillis();
        m.put("status", "UP");
        m.put("latencyMs", System.currentTimeMillis() - start);
        m.put("detail", "进程存活");
        return m;
    }

    private Map<String, Object> checkDatabase() {
        Map<String, Object> m = layer("db", "MySQL");
        long start = System.currentTimeMillis();
        try {
            Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
            m.put("latencyMs", System.currentTimeMillis() - start);
            if (one != null && one == 1) {
                m.put("status", "UP");
                m.put("detail", "SELECT 1 通过");
            } else {
                m.put("status", "DOWN");
                m.put("detail", "SELECT 1 返回异常值: " + one);
            }
        } catch (Exception e) {
            m.put("latencyMs", System.currentTimeMillis() - start);
            m.put("status", "DOWN");
            // 只给类型与消息前缀：健康检查面向运维，但也不该把整段堆栈甩到页面上
            m.put("detail", "查询失败: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + trim(e.getMessage())));
        }
        return m;
    }

    private Map<String, Object> checkCollection() {
        Map<String, Object> m = layer("collection", "采集服务（Registry 快照）");
        long start = System.currentTimeMillis();
        try {
            CollectionServiceClient.FetchResult result = snapshotProvider.fetchSnapshot();
            m.put("latencyMs", System.currentTimeMillis() - start);
            if (result != null && result.snapshot() != null) {
                int connectors = result.snapshot().capabilities() == null ? 0 : result.snapshot().capabilities().size();
                int caps = countCapabilities(result);
                m.put("status", "UP");
                m.put("detail", "快照可用，接入 " + connectors + " 个，能力声明 " + caps + " 条"
                        + (result.snapshot().diagnostics() == null || result.snapshot().diagnostics().isEmpty()
                        ? "" : "，诊断 " + result.snapshot().diagnostics().size() + " 条"));
            } else {
                m.put("status", "DOWN");
                m.put("detail", "快照不可用: "
                        + (result == null ? "无响应" : result.kind() + " " + safe(result.detail())));
            }
        } catch (Exception e) {
            m.put("latencyMs", System.currentTimeMillis() - start);
            m.put("status", "DOWN");
            m.put("detail", "采集服务探测异常: " + e.getClass().getSimpleName());
        }
        return m;
    }

    private Map<String, Object> checkAi() {
        Map<String, Object> m = layer("ai", "AI 服务（分词 / 分析 / 报告）");
        long start = System.currentTimeMillis();
        if (aiServiceUrl.isEmpty()) {
            m.put("status", "DEGRADED");
            m.put("latencyMs", 0);
            m.put("detail", "未配置 AI 服务地址");
            return m;
        }
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build()) {
            HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder(URI.create(aiServiceUrl + "/health"))
                            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            m.put("latencyMs", System.currentTimeMillis() - start);
            if (resp.statusCode() == 200) {
                m.put("status", "UP");
                m.put("detail", "健康端点返回 200");
            } else {
                m.put("status", "DEGRADED");
                m.put("detail", "健康端点返回 HTTP " + resp.statusCode() + "（AI 功能降级，采集不受影响）");
            }
        } catch (Exception e) {
            m.put("latencyMs", System.currentTimeMillis() - start);
            m.put("status", "DEGRADED");
            m.put("detail", "AI 服务不可达: " + e.getClass().getSimpleName() + "（AI 功能降级，采集不受影响）");
            log.debug("[health] AI 服务探测失败", e);
        }
        return m;
    }

    // ------------------------------------------------------------------
    private static Map<String, Object> layer(String code, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("layer", code);
        m.put("name", name);
        return m;
    }

    /** 能力声明的总条数（每个 connector 文档下挂一串能力）。 */
    private static int countCapabilities(CollectionServiceClient.FetchResult result) {
        try {
            return result.snapshot().capabilities().stream()
                    .mapToInt(doc -> doc.capabilities() == null ? 0 : doc.capabilities().size())
                    .sum();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String trim(String s) {
        return s.length() <= 120 ? s : s.substring(0, 120) + "…";
    }
}
