package com.coolpilot.insight.source;

import com.coolpilot.insight.AbstractIntegrationTest;
import com.coolpilot.insight.common.security.JwtUtil;
import com.coolpilot.insight.utils.TokenStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据源能力接口的**端到端**测试（SOURCE-04-06 / SOURCE-04-08 的一部分）。
 *
 * <p>为什么要起真实 HTTP：鉴权结论只由拦截器在真实请求链路上给出。
 * 直接调用 service 永远测不到"没带 Token 会怎样"——
 * 那正是上一轮 SOURCE-04-02 出过的问题（排除路径导致接口裸奔）。
 *
 * <p>四个覆盖面：
 * <ol>
 *   <li>Flyway 迁移把种子 v1 行迁成 v2（映射规则与 SOURCE-04-04 §2.2 一致）；</li>
 *   <li>迁移脚本可重入，且对不可解释的 v1 行保持不动（不猜测字段含义）；</li>
 *   <li>未登录 → 401，已登录无权限 → 403（两个方向都测，避免"有注解等于有鉴权"的自证）；</li>
 *   <li>写入路径：非法请求不改变任何状态，合法请求落库并留审计。</li>
 * </ol>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SourceCapabilityApiTest extends AbstractIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASE = "/api/v1/source/sources/DOUYIN/capabilities";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private TokenStore tokenStore;

    @Value("${local.server.port}")
    private int port;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // ------------------------------------------------------------------
    // 1. 迁移结果
    // ------------------------------------------------------------------
    @Test
    @Order(1)
    @DisplayName("迁移：种子 v1 行被迁成 v2，三项能力的映射与冻结规则一致")
    void flywayMigrationProducedV2() {
        JsonNode doc = currentCapability("DOUYIN");

        assertThat(doc.path("schemaVersion").asText()).isEqualTo("2.0");

        JsonNode caps = doc.path("capabilities");
        assertThat(caps).hasSize(3);

        assertThat(caps).allSatisfy(c -> {
            assertThat(c.path("sourceCode").asText()).isEqualTo("DOUYIN");
            assertThat(c.path("connectorCode").asText()).isEqualTo("DOUYIN_PUBLIC");
            assertThat(c.path("accessModes")).isNotEmpty();
            assertThat(c.path("desiredStatus").asText()).isIn("ENABLED", "DISABLED");
        });

        // 旧状态词 PENDING 不得出现在新结构里（SOURCE-04-04 §2.4）
        assertThat(doc.toString()).doesNotContain("PENDING").doesNotContain("commentStatus");

        assertThat(capability(doc, "COMMENT").path("desiredStatus").asText()).isEqualTo("ENABLED");
        assertThat(capability(doc, "COMMENT").path("category").asText()).isEqualTo("COMMENT");
        assertThat(capability(doc, "COMMENT").path("accessModes").toString())
                .isEqualTo("[\"PUBLIC\",\"AUTHENTICATED\"]");

        assertThat(capability(doc, "CONTENT_DETAIL").path("desiredStatus").asText())
                .isEqualTo("ENABLED");
        assertThat(capability(doc, "KEYWORD_SEARCH").path("desiredStatus").asText())
                .isEqualTo("DISABLED");
    }

    // ------------------------------------------------------------------
    // 2. 可重入 + fail-closed
    // ------------------------------------------------------------------
    @Test
    @Order(2)
    @DisplayName("迁移：可重入；非布尔字段的 v1 行保持不动（不猜测含义）")
    void migrationIsReentrantAndFailClosed() throws IOException {
        String sql = migrationSql();

        try {
            insertSource("XIAOHONGSHU", "小红书",
                    "{\"comments\":true,\"contentDetail\":false,\"keywordSearch\":false}");
            insertSource("WEB", "网页", "{\"comments\":\"yes\",\"contentDetail\":true}");

            jdbc.execute(sql);
            String once = rawCapability("XIAOHONGSHU");
            JsonNode doc = JSON.readTree(once);

            assertThat(doc.path("schemaVersion").asText()).isEqualTo("2.0");
            assertThat(doc.path("capabilities")).hasSize(3);
            assertThat(doc.path("capabilities").get(0).path("connectorCode").asText())
                    .isEqualTo("XIAOHONGSHU_PUBLIC");
            assertThat(capability(doc, "CONTENT_DETAIL").path("desiredStatus").asText())
                    .isEqualTo("DISABLED");

            // 一个布尔字段都不是的行：整行保持 v1，交给运行时诊断
            assertThat(rawCapability("WEB"))
                    .contains("\"comments\"")
                    .contains("\"yes\"")
                    .doesNotContain("schemaVersion");

            // 再跑一次：已经是 v2 的行不得被改写
            jdbc.execute(sql);
            assertThat(rawCapability("XIAOHONGSHU")).isEqualTo(once);
        } finally {
            jdbc.update("DELETE FROM ds_source WHERE code IN ('XIAOHONGSHU','WEB')");
        }
    }

    // ------------------------------------------------------------------
    // 3. 鉴权
    // ------------------------------------------------------------------
    @Test
    @Order(3)
    @DisplayName("鉴权：未携带 Token → 401（读与写都不得裸奔）")
    void rejectsAnonymous() throws Exception {
        assertThat(send("GET", BASE, null, null).statusCode()).isEqualTo(401);
        assertThat(send("PUT", BASE, "{}", null).statusCode()).isEqualTo(401);
    }

    @Test
    @Order(4)
    @DisplayName("鉴权：已登录但无权限码 → 403（注解存在不等于权限生效）")
    void rejectsWithoutPermission() throws Exception {
        String token = tokenFor("普通用户", 0, null);

        assertThat(send("GET", BASE, null, token).statusCode()).isEqualTo(403);
        assertThat(send("PUT", BASE, "{}", token).statusCode()).isEqualTo(403);
    }

    // ------------------------------------------------------------------
    // 4. 写路径
    // ------------------------------------------------------------------
    @Test
    @Order(5)
    @DisplayName("写：非法请求 → 400，且库中内容与 Source 状态都不变")
    void invalidWriteChangesNothing() throws Exception {
        String token = adminToken();
        String before = rawCapability("DOUYIN");
        String statusBefore = jdbc.queryForObject(
                "SELECT status FROM ds_source WHERE code='DOUYIN'", String.class);

        // v1 结构：旧结构不得再产生新写入
        var r = send("PUT", BASE,
                "{\"schemaVersion\":\"1.0\",\"capabilities\":[]}", token);

        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).contains("不接受 Desired v1 写入");
        assertThat(rawCapability("DOUYIN")).isEqualTo(before);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM ds_source WHERE code='DOUYIN'", String.class))
                .isEqualTo(statusBefore);
    }

    @Test
    @Order(6)
    @DisplayName("写：合法请求 → 200，落库为规范化 v2，并留下 before/after 审计")
    void validWritePersistsAndAudits() throws Exception {
        String token = adminToken();
        String statusBefore = jdbc.queryForObject(
                "SELECT status FROM ds_source WHERE code='DOUYIN'", String.class);
        Long sourceId = jdbc.queryForObject(
                "SELECT id FROM ds_source WHERE code='DOUYIN'", Long.class);

        String body = "{\"schemaVersion\":\"2.0\",\"capabilities\":["
                + "{\"sourceCode\":\"DOUYIN\",\"connectorCode\":\"DOUYIN_PUBLIC\","
                + "\"code\":\"COMMENT\",\"category\":\"COMMENT\","
                + "\"accessModes\":[\"PUBLIC\",\"AUTHENTICATED\"],\"desiredStatus\":\"ENABLED\"},"
                + "{\"sourceCode\":\"DOUYIN\",\"connectorCode\":\"DOUYIN_PUBLIC\","
                + "\"code\":\"KEYWORD_SEARCH\",\"category\":\"SEARCH\","
                + "\"accessModes\":[\"PUBLIC\"],\"desiredStatus\":\"DISABLED\"}]}";

        var r = send("PUT", BASE, body, token);

        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode data = JSON.readTree(r.body()).path("data");
        assertThat(data.path("desired")).hasSize(2);
        assertThat(data.path("desiredError").isNull()).isTrue();
        assertThat(data.path("effective")).isNotEmpty();
        assertThat(data.path("snapshotState").asText()).isIn("FRESH", "STALE", "UNAVAILABLE");

        JsonNode persisted = currentCapability("DOUYIN");
        assertThat(persisted.path("schemaVersion").asText()).isEqualTo("2.0");
        assertThat(persisted.path("capabilities")).hasSize(2);
        // 只读能力 + 写入顺序被规范化：第一个是 COMMENT
        assertThat(persisted.path("capabilities").get(0).path("code").asText())
                .isEqualTo("COMMENT");

        // 写能力声明不得改 Source 状态，也不得碰账号状态
        assertThat(jdbc.queryForObject(
                "SELECT status FROM ds_source WHERE code='DOUYIN'", String.class))
                .isEqualTo(statusBefore);

        String detail = jdbc.queryForObject(
                "SELECT detail_json FROM audit_log WHERE action='SOURCE_CAPABILITY_UPDATE'"
                        + " AND resource_id=? ORDER BY id DESC LIMIT 1",
                String.class, sourceId);
        assertThat(detail).contains("\"before\"").contains("\"after\"");
        assertThat(detail).contains("KEYWORD_SEARCH");
    }

    @Test
    @Order(7)
    @DisplayName("读：返回三态明细，Observed 只含真实接入（STUB 不在其中）")
    void detailReturnsThreeStates() throws Exception {
        var r = send("GET", BASE, null, adminToken());

        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode data = JSON.readTree(r.body()).path("data");

        assertThat(data.path("sourceCode").asText()).isEqualTo("DOUYIN");
        assertThat(data.path("desired").isArray()).isTrue();
        assertThat(data.path("effective").isArray()).isTrue();
        assertThat(data.path("observed").isArray()).isTrue();

        // 容器里 Registry 可能不可达：两种状态都必须如实，不得伪造成"有观测"
        String state = data.path("snapshotState").asText();
        assertThat(state).isIn("FRESH", "STALE", "UNAVAILABLE");
        if ("UNAVAILABLE".equals(state)) {
            assertThat(data.path("observed")).isEmpty();
        } else {
            assertThat(data.path("observed")).allSatisfy(c -> {
                assertThat(c.path("platformScoped").asBoolean()).isTrue();
                assertThat(c.path("connectorCode").asText()).isNotEqualTo("STUB_PUBLIC");
            });
        }
    }

    // ------------------------------------------------------------------
    // 装配
    // ------------------------------------------------------------------
    private record Response(int statusCode, String body) {
    }

    private Response send(String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20));
        if (token != null) {
            b.header("Authorization", token);
        }
        if ("PUT".equals(method)) {
            b.header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            b.GET();
        }
        HttpResponse<String> resp = http.send(b.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Response(resp.statusCode(), resp.body());
    }

    /** 建用户并（可选）授予角色，返回可用 Token。 */
    private String tokenFor(String username, int role, String roleCode) {
        String phone = role == 1 ? "19900000001" : "19900000002";
        List<Long> existing = jdbc.queryForList(
                "SELECT id FROM `user` WHERE phone=?", Long.class, phone);
        Long userId;
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO `user` (`username`,`phone`,`password_hash`,`role`,`status`)"
                    + " VALUES (?,?,?,?,1)", username, phone, "x", role);
            userId = jdbc.queryForObject("SELECT id FROM `user` WHERE phone=?", Long.class, phone);
        } else {
            userId = existing.get(0);
        }
        if (roleCode != null) {
            jdbc.update("INSERT INTO sys_user_role (user_id, role_id) SELECT ?, id FROM sys_role"
                    + " WHERE code=? AND NOT EXISTS (SELECT 1 FROM sys_user_role x"
                    + " WHERE x.user_id=? AND x.role_id=(SELECT id FROM sys_role WHERE code=?))",
                    userId, roleCode, userId, roleCode);
        }
        String token = jwtUtil.createToken(userId, role);
        tokenStore.put(token, userId, 3_600_000L);
        return token;
    }

    private String adminToken() {
        return tokenFor("能力测试管理员", 1, "ADMIN");
    }

    private void insertSource(String code, String name, String capabilityJson) {
        jdbc.update("DELETE FROM ds_source WHERE code=?", code);
        jdbc.update("INSERT INTO ds_source (`code`,`name`,`source_type`,`adapter_code`,"
                        + "`status`,`capability_json`) VALUES (?,?,?,?,?,?)",
                code, name, "TEST", code.toLowerCase(), "ENABLED", capabilityJson);
    }

    private String rawCapability(String code) {
        return jdbc.queryForObject(
                "SELECT capability_json FROM ds_source WHERE code=?", String.class, code);
    }

    private JsonNode currentCapability(String code) {
        try {
            return JSON.readTree(rawCapability(code));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode capability(JsonNode doc, String code) {
        for (JsonNode c : doc.path("capabilities")) {
            if (code.equals(c.path("code").asText())) {
                return c;
            }
        }
        throw new AssertionError("迁移结果缺少能力: " + code);
    }

    private String migrationSql() throws IOException {
        String raw = new String(new ClassPathResource("db/migration/V31__source_capability_v2.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\n")) {
            if (line.strip().startsWith("--")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        String sql = sb.toString().strip();
        assertThat(sql).startsWith("UPDATE");
        return sql;
    }
}
