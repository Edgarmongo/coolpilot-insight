package com.coolpilot.insight.system.application;

import com.coolpilot.insight.system.domain.AuditLog;
import com.coolpilot.insight.system.infrastructure.persistence.AuditLogMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

/**
 * 操作审计。
 *
 * <h3>{@code detail_json} 必须是合法 JSON</h3>
 * 库中该列是 MySQL {@code JSON} 类型。历史实现把调用方传来的字符串**原样**写入，
 * 而多数调用方传的是纯文本（{@code "items=1"}、{@code "markdown"}、视频 ID…），
 * MySQL 直接拒收（error 3140），异常又被 catch 成一行 warn —— 于是
 * 这些审计事件**一直在静默丢失**。这不是"偶发写入失败"，是必定失败。
 *
 * <p>现在统一在写入前规范化：
 * <ul>
 *   <li>空值 → {@code NULL}（不是字符串 {@code "null"}）；</li>
 *   <li>已是合法 JSON → 解析后重新序列化（同时起到校验与压缩作用）；</li>
 *   <li>纯文本 → 包成 {@code {"text": "..."}}，内容一字不改地保留。</li>
 * </ul>
 *
 * <h3>失败语义</h3>
 * 审计是旁路：**绝不影响业务结果**。但"不影响"不等于"可以不报"——
 * 序列化失败与写入失败都记 ERROR 并计数（{@code audit.failures}），
 * 否则问题会像以前一样沉在水下。
 *
 * <p>序列化失败时仍然写审计行，只是详情置空：宁可少一条详情，
 * 也不能丢"谁在什么时候做了什么"这件事。
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    /** 与系统其它地方保持一致：局部 Jackson 实例，不依赖容器里是否注册了 bean。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 纯文本详情统一包在这个字段下，保证列类型合法且原文可追溯。 */
    static final String TEXT_FIELD = "text";

    private final AuditLogMapper auditMapper;
    private final PermissionService permissionService;
    private final ObjectProvider<MeterRegistry> meterRegistry;

    public AuditService(AuditLogMapper auditMapper, PermissionService permissionService,
                        ObjectProvider<MeterRegistry> meterRegistry) {
        this.auditMapper = auditMapper;
        this.permissionService = permissionService;
        this.meterRegistry = meterRegistry;
    }

    public void audit(String action, String resourceType, Long resourceId, String detail) {
        String detailJson = null;
        try {
            detailJson = toJsonDocument(detail);
        } catch (Exception e) {
            // 序列化失败不能静默：这条错误本身说明传入了无法表达的内容
            log.error("[audit][SERIALIZE_FAILED] action={} resourceType={} resourceId={} detail={}",
                    action, resourceType, resourceId, abbreviate(detail), e);
            countFailure(action, "serialize");
            // 详情留空但审计行照写：丢详情可以，丢"谁做了什么"不行
        }
        persist(action, resourceType, resourceId, detailJson);
    }

    /**
     * 结构化详情。调用方给出键值与业务语义，序列化交给这里——
     * <p>不重载 {@code audit(...)}：与 {@code detail=null} 的调用点会产生歧义，
     * 编译期就得靠强转区分，属于给调用方挖坑。
     * 这样 {@code detail_json} 的形状由一处决定，不会出现
     * "同样是提交任务，一处是对象、一处是裸字符串"。
     */
    public void auditStructured(String action, String resourceType, Long resourceId,
                                Map<String, ?> detail) {
        String detailJson = null;
        if (detail != null && !detail.isEmpty()) {
            try {
                detailJson = JSON.writeValueAsString(detail);
            } catch (Exception e) {
                log.error("[audit][SERIALIZE_FAILED] action={} resourceType={} resourceId={} keys={}",
                        action, resourceType, resourceId, detail.keySet(), e);
                countFailure(action, "serialize");
            }
        }
        persist(action, resourceType, resourceId, detailJson);
    }

    private void persist(String action, String resourceType, Long resourceId, String detailJson) {
        try {
            AuditLog entry = new AuditLog();
            entry.setUserId(permissionService.currentUserId());
            entry.setAction(action);
            entry.setResourceType(resourceType);
            entry.setResourceId(resourceId);
            entry.setDetailJson(detailJson);

            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest req = attrs.getRequest();
                entry.setClientIp(clientIp(req));
                String ua = req.getHeader("User-Agent");
                entry.setUserAgent(ua == null ? null : ua.substring(0, Math.min(ua.length(), 255)));
            }
            auditMapper.insert(entry);
        } catch (Exception e) {
            // 审计失败不改变业务结果，但必须可观测：ERROR 日志 + 计数器
            log.error("[audit][WRITE_FAILED] action={} resourceType={} resourceId={} detailJson={}",
                    action, resourceType, resourceId, abbreviate(detailJson), e);
            countFailure(action, "write");
        }
    }

    /**
     * 把任意详情规范化为合法 JSON 文本。
     *
     * @return 合法 JSON；{@code null} 表示不写详情
     */
    static String toJsonDocument(String detail) throws Exception {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(detail);
            if (node != null && !node.isMissingNode()) {
                return JSON.writeValueAsString(node);
            }
        } catch (Exception notJson) {
            // 不是 JSON —— 落到下面的纯文本包装。**这里不能直接抛出**：
            // 纯文本是常态（22 个调用点里大半传的是 ID、状态、文件名），
            // 把它当"序列化失败"会把正常调用变成错误日志。
        }
        return JSON.writeValueAsString(Map.of(TEXT_FIELD, detail));
    }

    /**
     * 失败计数。**整个方法是一个异常隔离区**。
     *
     * <p>它被调用的时机永远在"审计已经出问题之后"，因此这里再抛异常，
     * 异常会从 {@code audit()} 的 catch 块里向外穿透（catch 块内的异常
     * 不会被它自己捕获），直接落到业务调用方身上——
     * 那等于"审计的旁路故障反向破坏了已经成功的业务"。
     *
     * <p>所以：{@code getIfAvailable()} 也必须放进 try。它看起来只是个 getter，
     * 但在容器未就绪/依赖解析失败时会抛 {@code BeansException}。
     *
     * <p>边界要清楚（不吞掉程序错误）：
     * <ul>
     *   <li>指标组件不可用 → WARN（一行，带异常类型），业务继续；</li>
     *   <li>计数注册失败 → WARN，业务继续；</li>
     *   <li>任何情况下都**不向调用方抛出**。</li>
     * </ul>
     */
    private void countFailure(String action, String stage) {
        try {
            MeterRegistry registry = meterRegistry == null ? null : meterRegistry.getIfAvailable();
            if (registry == null) {
                log.debug("[audit][METRICS_UNAVAILABLE] action={} stage={} 无 MeterRegistry，计数跳过",
                        action, stage);
                return;
            }
            Counter.builder("audit.failures")
                    .description("审计写入/序列化失败次数")
                    .tag("stage", stage)
                    .tag("action", action == null ? "unknown" : action)
                    .register(registry)
                    .increment();
        } catch (RuntimeException e) {
            // 注意这里只捕获 RuntimeException（指标 API 的实际抛出类型），
            // 不写 catch (Throwable)：Error（OOM 等）不该被审计旁路吞掉。
            log.warn("[audit][METRICS_FAILED] action={} stage={} 计数失败: {}",
                    action, stage, e.toString());
        }
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    private String clientIp(HttpServletRequest req) {
        String ip = req.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isEmpty()) {
            int comma = ip.indexOf(',');
            return comma > 0 ? ip.substring(0, comma).trim() : ip.trim();
        }
        return req.getRemoteAddr();
    }
}
