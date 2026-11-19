package com.coolpilot.insight.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求级 Trace ID。
 *
 * <p>写入 MDC 供日志使用，同时回写到响应头 {@code X-Trace-Id}，
 * 前端报错时可以把该值贴给后端检索日志。
 *
 * <p>必须 {@code finally} 清理 MDC：Tomcat 线程会复用，不清会串到下一个请求上
 * （与 {@code RequestContext} 同类问题）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = sanitize(request.getHeader(HEADER));
        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * 校验并规整客户端传入的 traceId。
     *
     * <p>只允许 {@code [A-Za-z0-9_-]} 且长度 8~64：
     * - 太短（<8）缺乏区分度，容易撞
     * - 含换行/空格等字符会被日志注入（伪造日志行）
     * - 非法输入一律**丢弃并生成新的**，而不是拒绝请求——traceId 是观测手段，
     *   不应因为它让正常业务失败
     */
    static String sanitize(String raw) {
        if (raw == null) {
            return generate();
        }
        String v = raw.trim();
        if (v.length() < 8 || v.length() > 64) {
            return generate();
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) {
                return generate();
            }
        }
        return v;
    }

    private static String generate() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** 当前请求的 traceId；无上下文时返回 null。 */
    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
