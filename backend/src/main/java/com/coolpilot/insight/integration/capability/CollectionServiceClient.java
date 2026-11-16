package com.coolpilot.insight.integration.capability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Collection Service 统一快照的 HTTP 客户端（SOURCE-04-02 §A）。
 *
 * <p>只调用**一个**端点 {@code GET /v1/registry/snapshot}。
 * <b>不</b>分别调用 {@code /v1/capabilities} 与 {@code /v1/adapters}——
 * 那样会引入跨请求的版本竞争（见 {@link RegistrySnapshot} 的说明）。
 *
 * <p>失败分类明确，供上层映射为不同的 {@code effectiveReason}：
 *
 * <table border="1">
 *   <tr><th>Kind</th><th>触发</th><th>上层映射</th></tr>
 *   <tr><td>{@code UNREACHABLE}</td><td>连接失败 / 超时</td><td>{@code REGISTRY_UNREACHABLE}</td></tr>
 *   <tr><td>{@code HTTP_ERROR}</td><td>非 200（含 401 令牌错误）</td><td>{@code REGISTRY_UNREACHABLE}</td></tr>
 *   <tr><td>{@code UNPARSEABLE}</td><td>响应非法 JSON / 缺必需字段</td><td>{@code SCHEMA_UNSUPPORTED}</td></tr>
 *   <tr><td>{@code SCHEMA_UNSUPPORTED}</td><td>Schema 主版本不支持</td><td>{@code SCHEMA_UNSUPPORTED}</td></tr>
 *   <tr><td>{@code INCONSISTENT}</td><td>能力清单与诊断互相矛盾</td><td>{@code SCHEMA_UNSUPPORTED}</td></tr>
 * </table>
 */
@Component
public class CollectionServiceClient implements SnapshotProvider {

    private static final Logger log = LoggerFactory.getLogger(CollectionServiceClient.class);

    /** 快照端点的路径。 */
    static final String SNAPSHOT_PATH = "/v1/registry/snapshot";

    private static final int CONNECT_TIMEOUT_SECONDS = 2;
    private static final int REQUEST_TIMEOUT_SECONDS = 5;

    private final String baseUrl;
    private final String internalToken;

    public CollectionServiceClient(
            @Value("${app.collection-service-url:http://127.0.0.1:5001}") String baseUrl,
            @Value("${app.collection-internal-token:}") String internalToken) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.internalToken = internalToken == null ? "" : internalToken.trim();
    }

    /**
     * 拉取一次快照。
     *
     * @return 成功时含完整快照；失败时含分类与说明，`snapshot` 为 {@code null}
     */
    @Override
    public FetchResult fetchSnapshot() {
        if (baseUrl.isEmpty()) {
            return FetchResult.failure(FetchResult.Kind.UNREACHABLE, "未配置采集服务地址");
        }
        String body;
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS)).build()) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(
                            URI.create(baseUrl + SNAPSHOT_PATH))
                    .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                    .GET();
            if (!internalToken.isEmpty()) {
                builder.header("X-Internal-Token", internalToken);
            }
            HttpResponse<String> resp = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return FetchResult.failure(FetchResult.Kind.HTTP_ERROR,
                        "快照端点返回 HTTP " + resp.statusCode());
            }
            body = resp.body();
        } catch (Exception e) {
            log.warn("[capability] 拉取 Registry 快照失败: {}", e.toString());
            return FetchResult.failure(FetchResult.Kind.UNREACHABLE,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        RegistrySnapshotParser.Result parsed = RegistrySnapshotParser.parse(body);
        if (!parsed.ok()) {
            // 机器分派**只读结构化原因码**，message 只进日志。
            // 改措辞、改语言都不会影响分类（见 SOURCE-04-02 评审 H1）。
            RegistrySnapshotParser.Failure failure = parsed.error().failure();
            FetchResult.Kind kind = mapFailure(failure);
            log.warn("[capability] 快照被拒绝（{} / {}）: {}",
                    kind, failure, parsed.error().message());
            return FetchResult.failure(kind, parsed.error().message());
        }
        return new FetchResult(parsed.snapshot(), FetchResult.Kind.OK, null);
    }

    /**
     * 解析失败码 → 传输层失败分类。
     *
     * <p>这是一张**穷举**映射表（switch 表达式，新增 {@code Failure} 会编译报错），
     * 不是 if-else 兜底——新增原因码时强制要求显式决定如何分类。
     */
    static FetchResult.Kind mapFailure(RegistrySnapshotParser.Failure failure) {
        return switch (failure) {
            case UNSUPPORTED_SCHEMA -> FetchResult.Kind.SCHEMA_UNSUPPORTED;
            case UNSUPPORTED_CONTRACT -> FetchResult.Kind.CONTRACT_UNSUPPORTED;
            case INCONSISTENT -> FetchResult.Kind.INCONSISTENT;
            case MALFORMED_BODY, MISSING_SNAPSHOT_ID, MALFORMED_SNAPSHOT_ID,
                 MISSING_ARRAY, BROKEN_CAPABILITY -> FetchResult.Kind.MALFORMED_RESPONSE;
        };
    }

    public String baseUrl() {
        return baseUrl;
    }

    /**
     * 拉取结果。
     *
     * @param snapshot 成功时的完整快照
     * @param kind     失败分类
     * @param detail   失败说明（人类可读，**不用于机器判断**）
     */
    public record FetchResult(RegistrySnapshot snapshot, Kind kind, String detail) {

        public enum Kind {
            /** 成功 */
            OK,
            /** 连接失败 / 超时 */
            UNREACHABLE,
            /** 非 200 */
            HTTP_ERROR,
            /** 非法 JSON / 缺必需字段 / 快照 ID 格式非法 */
            MALFORMED_RESPONSE,
            /** Schema 主版本不支持 */
            SCHEMA_UNSUPPORTED,
            /** 快照契约主版本不支持 */
            CONTRACT_UNSUPPORTED,
            /** 能力清单与诊断互相矛盾 */
            INCONSISTENT
        }

        public boolean ok() {
            return kind == Kind.OK && snapshot != null;
        }

        public static FetchResult failure(Kind kind, String detail) {
            return new FetchResult(null, kind, detail);
        }
    }
}
