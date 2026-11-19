package com.coolpilot.insight.integration.health;

/**
 * 服务可达性探测。
 *
 * <p>存在的理由：{@code AccountVerificationService} 曾经把 <b>ai-service</b> 的
 * {@code /health} 当成 <b>collection-service</b> 的可达性来判断——两者都返回 200，
 * 于是错误长期没被发现，直到把采集服务拆成独立容器才暴露出来。
 *
 * <p>本类把"探测哪个服务"变成显式参数，从结构上杜绝张冠李戴。
 * 不同服务必须用不同的实例、不同的 URL、不同的名字。
 */
public final class ServiceHealthProbe {

    /** 单次探测的超时（秒）。 */
    private static final int CONNECT_TIMEOUT_SECONDS = 2;
    private static final int REQUEST_TIMEOUT_SECONDS = 3;

    private final String serviceName;
    private final String baseUrl;

    public ServiceHealthProbe(String serviceName, String baseUrl) {
        this.serviceName = serviceName;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
    }

    public String serviceName() {
        return serviceName;
    }

    public String baseUrl() {
        return baseUrl;
    }

    /**
     * 探测服务是否可达。
     *
     * <p>只调用 {@code GET {baseUrl}/health}，**不触发任何平台采集请求**。
     *
     * @return 探测结论；不可达时 {@code detail} 说明原因
     */
    public Result probe() {
        if (baseUrl.isEmpty()) {
            return new Result(serviceName, baseUrl, false, "未配置服务地址");
        }
        try (java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS)).build()) {
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(
                            java.net.URI.create(baseUrl + "/health"))
                    .timeout(java.time.Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                    .GET().build();
            java.net.http.HttpResponse<String> resp =
                    client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return new Result(serviceName, baseUrl, true, "HTTP 200");
            }
            return new Result(serviceName, baseUrl, false, "HTTP " + resp.statusCode());
        } catch (Exception e) {
            return new Result(serviceName, baseUrl, false,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * 探测结论。
     *
     * @param serviceName 服务标识（如 {@code AI_SERVICE} / {@code COLLECTION_SERVICE}）
     * @param baseUrl 被探测的地址
     * @param reachable 是否可达
     * @param detail 细节（失败原因或成功标记）
     */
    public record Result(String serviceName, String baseUrl, boolean reachable, String detail) {
    }
}
