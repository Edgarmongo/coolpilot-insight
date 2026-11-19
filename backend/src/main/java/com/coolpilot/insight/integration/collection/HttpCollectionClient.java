package com.coolpilot.insight.integration.collection;

import com.coolpilot.insight.integration.common.CommandResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/**
 * 采集执行走 HTTP 作业（SOURCE-06）。
 *
 * <h3>为什么是"提交 → 轮询 → 收尾"</h3>
 * 采集耗时 10~120 秒，同步 HTTP 必然超时。契约因此是异步作业：
 * {@code POST /v1/jobs} 立刻返回 jobId，状态机在服务端推进。
 *
 * <h3>失败必须可分辨</h3>
 * 401/403、404（派发目标不存在）、409（能力不可执行）、超时、作业 FAILED
 * 是**五种完全不同的事**，排查方向互不相同。全部映射成一个 "采集失败"
 * 等于让运维从零开始猜——因此 {@link CommandResult#output()} 里一定带上
 * 服务端返回的 code 与 message，以及作业的日志尾巴。
 *
 * @see <a href="SOURCE-06-TASKCARD.md">SOURCE-06 任务卡</a>
 */
public class HttpCollectionClient implements CollectionClient {

    private static final Logger log = LoggerFactory.getLogger(HttpCollectionClient.class);

    static final String JOBS_PATH = "/v1/jobs";

    private static final int CONNECT_TIMEOUT_SECONDS = 2;
    private static final int SUBMIT_TIMEOUT_SECONDS = 10;
    private static final int POLL_INTERVAL_MS = 2000;

    /** 与子进程实现的超时保持一致：翻页较久时给 10 分钟。 */
    private static final int COLLECT_TIMEOUT_SECONDS = 600;
    private static final int SEARCH_TIMEOUT_SECONDS = 300;

    private final String baseUrl;
    private final String internalToken;
    private final String defaultConnector;
    private final ProcessCollectionClient processFallback;
    private final int pollIntervalMs;
    private final int collectTimeoutSeconds;
    private final int searchTimeoutSeconds;

    private final ObjectMapper json = new ObjectMapper();

    public HttpCollectionClient(String baseUrl, String internalToken, String defaultConnector,
                                ProcessCollectionClient processFallback) {
        this(baseUrl, internalToken, defaultConnector, processFallback,
                POLL_INTERVAL_MS, COLLECT_TIMEOUT_SECONDS, SEARCH_TIMEOUT_SECONDS);
    }

    /** 轮询间隔与超时可注入，便于测试不必真的等 2 秒 / 10 分钟。 */
    HttpCollectionClient(String baseUrl, String internalToken, String defaultConnector,
                         ProcessCollectionClient processFallback, int pollIntervalMs) {
        this(baseUrl, internalToken, defaultConnector, processFallback,
                pollIntervalMs, COLLECT_TIMEOUT_SECONDS, SEARCH_TIMEOUT_SECONDS);
    }

    HttpCollectionClient(String baseUrl, String internalToken, String defaultConnector,
                         ProcessCollectionClient processFallback, int pollIntervalMs,
                         int collectTimeoutSeconds, int searchTimeoutSeconds) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.internalToken = internalToken == null ? "" : internalToken.trim();
        this.defaultConnector = defaultConnector == null || defaultConnector.isBlank()
                ? "DOUYIN_PUBLIC" : defaultConnector.trim();
        this.processFallback = processFallback;
        this.pollIntervalMs = Math.max(pollIntervalMs, 1);
        this.collectTimeoutSeconds = Math.max(collectTimeoutSeconds, 1);
        this.searchTimeoutSeconds = Math.max(searchTimeoutSeconds, 1);
    }

    @Override
    public CommandResult collectComments(String externalContentId, int maxComments,
                                         boolean useLogin, Long taskId, Long itemId) {
        String jobId = taskId != null
                ? "task-" + taskId + "-item-" + (itemId == null ? "x" : itemId)
                : "java-" + UUID.randomUUID().toString().substring(0, 8);

        ObjectNode body = json.createObjectNode();
        body.put("jobId", jobId);
        body.put("connectorCode", defaultConnector);
        body.put("capabilityCode", "COMMENT");
        body.put("accessMode", useLogin ? "AUTHENTICATED" : "PUBLIC");
        body.put("externalContentId", externalContentId);
        body.put("maxItems", maxComments);
        body.putObject("params").put("useLogin", useLogin);

        return runJob(body, jobId, collectTimeoutSeconds, "comment");
    }

    /**
     * 关键词发现。
     *
     * <p><b>当前不可达</b>：{@code KEYWORD_SEARCH} 至今没有真实平台验证证据
     * （`SOURCE-01` 记录"风控行为未知"）。FIX-02 之后，所有执行入口都先经过
     * {@code AdmissionGatedCollectionClient}，该能力会在那里被判
     * {@code NOT_EXECUTABLE} 并拒绝——**本方法根本不会被调用**。
     *
     * <p>保留这段委派是给"证据齐备之后"用的：届时把它换成一个真正的
     * {@code POST /v1/jobs(capabilityCode=KEYWORD_SEARCH)} 即可，
     * 闸门的判定逻辑不需要改。
     */
    @Override
    public CommandResult searchByKeyword(String keyword, int max) {
        if (processFallback == null) {
            return new CommandResult(1, "关键词搜索仍依赖子进程实现，但回退实例不可用", 0);
        }
        log.debug("[collection] 关键词搜索走子进程（该能力未取得验证证据，不进入 HTTP 作业派发）");
        return processFallback.searchByKeyword(keyword, max);
    }

    // ------------------------------------------------------------------
    // 作业生命周期
    // ------------------------------------------------------------------
    private CommandResult runJob(ObjectNode body, String jobId, int timeoutSeconds, String kind) {
        long started = System.currentTimeMillis();
        if (baseUrl.isEmpty()) {
            return fail(started, "未配置采集服务地址（app.collection-service-url）");
        }

        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS)).build()) {

            // ---- 提交 ----
            HttpRequest submit = request("").POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .timeout(Duration.ofSeconds(SUBMIT_TIMEOUT_SECONDS))
                    .header("Content-Type", "application/json")
                    .build();
            HttpResponse<String> submitResp = client.send(submit, HttpResponse.BodyHandlers.ofString());
            if (submitResp.statusCode() != 202) {
                return fail(started, describeRejection(kind, submitResp));
            }

            // ---- 轮询 ----
            long deadline = started + timeoutSeconds * 1000L;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(pollIntervalMs);
                HttpResponse<String> statusResp = client.send(
                        request("/" + jobId).GET().timeout(Duration.ofSeconds(10)).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (statusResp.statusCode() != 200) {
                    return fail(started, "查询作业状态失败: HTTP " + statusResp.statusCode());
                }
                JsonNode st = json.readTree(statusResp.body());
                String status = text(st, "status");
                if ("SUCCEEDED".equals(status)) {
                    return new CommandResult(0, summarize(st), System.currentTimeMillis() - started);
                }
                if ("FAILED".equals(status) || "CANCELLED".equals(status)) {
                    return fail(started, summarize(st));
                }
                if ("PARTIAL".equals(status)) {
                    // PARTIAL 表示"一个数据包都没收到"（见服务端 0 条语义），是失败而非完成
                    return fail(started, summarize(st));
                }
            }

            // ---- 超时：尽力取消，但不假装成功 ----
            cancelQuietly(client, jobId);
            return fail(started, "作业超时（" + timeoutSeconds + "s）：jobId=" + jobId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail(started, "轮询被中断：jobId=" + jobId);
        } catch (Exception e) {
            return fail(started, "采集作业调用失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private HttpRequest.Builder request(String pathSuffix) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + JOBS_PATH + pathSuffix))
                .timeout(Duration.ofSeconds(10));
        if (!internalToken.isEmpty()) {
            b.header("X-Internal-Token", internalToken);
        }
        return b;
    }

    private void cancelQuietly(HttpClient client, String jobId) {
        try {
            client.send(request("/" + jobId + "/cancel").POST(HttpRequest.BodyPublishers.noBody())
                            .timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.warn("[collection] 取消作业失败 jobId={}: {}", jobId, e.getMessage());
        }
    }

    /**
     * 把服务端的拒绝理由整理成人能读的文本。
     *
     * <p>服务端返回的 {@code code} 是稳定标识（{@code NOT_EXECUTABLE} 等），
     * 上游可以据此判断"是能力不可执行"还是"参数写错了"。
     */
    private String describeRejection(String kind, HttpResponse<String> resp) {
        String code = "";
        String message = "";
        try {
            JsonNode body = json.readTree(resp.body());
            code = text(body, "code");
            message = text(body, "message");
        } catch (Exception ignored) {
            message = resp.body();
        }
        return "采集服务拒绝了" + kind + "作业: HTTP " + resp.statusCode()
                + (code.isEmpty() ? "" : " [" + code + "]")
                + (message.isEmpty() ? "" : " " + message);
    }

    /**
     * 作业状态摘要。
     *
     * <p><b>只取结构性字段，绝不带 logTail 原文。</b>
     * 采集器的日志行形如 {@code 昵称｜抖音号｜属地｜赞数｜cid｜评论正文}，
     * 属于个人信息；它会顺着 {@code CommandResult.output()} 流进
     * 应用日志、任务日志表、item.error_message —— 三处都不该有它。
     *
     * <p>需要看具体内容时，用 {@code GET /v1/jobs/{{id}}} 在采集服务侧查（有令牌边界），
     * 或按 cid 到库里查（有数据权限）。
     */
    private String summarize(JsonNode status) {
        StringBuilder sb = new StringBuilder();
        sb.append("status=").append(text(status, "status"))
          .append(" collected=").append(number(status, "collected"))
          .append(" pages=").append(number(status, "pages"));
        String code = text(status, "errorCode");
        if (!code.isEmpty()) {
            sb.append(" errorCode=").append(code);
        }
        String msg = text(status, "errorMessage");
        if (!msg.isEmpty()) {
            sb.append(" message=").append(msg);
        }
        // 只报"有多少行"，不报内容
        JsonNode logs = status.get("logTail");
        if (logs != null && logs.isArray()) {
            sb.append(" logLines=").append(logs.size());
        }
        return sb.toString();
    }

    private CommandResult fail(long started, String output) {
        return new CommandResult(1, output, System.currentTimeMillis() - started);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }

    private static String number(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? "0" : v.asText();
    }
}
