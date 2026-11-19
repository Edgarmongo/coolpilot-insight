package com.coolpilot.insight.common;

import com.coolpilot.insight.common.web.TraceIdFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trace ID 过滤器（Batch 1 验收要求的 6 项检查）。
 *
 * <p>用 Spring 的 MockHttpServletRequest/Response，不起完整容器。
 */
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    private MockHttpServletResponse runWithHeader(String traceId) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/health");
        if (traceId != null) {
            req.addHeader(TraceIdFilter.HEADER, traceId);
        }
        MockHttpServletResponse resp = new MockHttpServletResponse();

        AtomicReference<String> mdcInChain = new AtomicReference<>();
        FilterChain chain = (rq, rs) -> mdcInChain.set(MDC.get(TraceIdFilter.MDC_KEY));

        filter.doFilter(req, resp, chain);
        assertThat(mdcInChain.get()).as("链路执行期间 MDC 应有值").isNotNull();
        return resp;
    }

    @Test
    @DisplayName("合法 traceId 被沿用")
    void keepsValidTraceId() throws Exception {
        MockHttpServletResponse resp = runWithHeader("trace-0001");
        assertThat(resp.getHeader(TraceIdFilter.HEADER)).isEqualTo("trace-0001");
    }

    @Test
    @DisplayName("长度不足 8 位被丢弃并重新生成")
    void rejectsTooShort() throws Exception {
        String got = runWithHeader("abc").getHeader(TraceIdFilter.HEADER);
        assertThat(got).hasSize(16).isNotEqualTo("abc");
    }

    @Test
    @DisplayName("超过 64 位被丢弃")
    void rejectsTooLong() throws Exception {
        String got = runWithHeader("a".repeat(65)).getHeader(TraceIdFilter.HEADER);
        assertThat(got).hasSize(16);
    }

    @Test
    @DisplayName("含非法字符（换行、空格、中文）被丢弃，防日志注入")
    void rejectsIllegalChars() throws Exception {
        for (String bad : new String[]{"bad\ninject", "has space", "中文traceid", "a';drop--"}) {
            String got = runWithHeader(bad).getHeader(TraceIdFilter.HEADER);
            assertThat(got).as("非法值 %s 应被替换", bad).hasSize(16).doesNotContain(bad);
        }
    }

    @Test
    @DisplayName("未传 traceId 时自动生成")
    void generatesWhenAbsent() throws Exception {
        assertThat(runWithHeader(null).getHeader(TraceIdFilter.HEADER)).hasSize(16);
    }

    @Test
    @DisplayName("请求结束后 MDC 必须清理，否则线程复用会串身份")
    void clearsMdcAfterRequest() throws Exception {
        runWithHeader("trace-clean");
        assertThat(MDC.get(TraceIdFilter.MDC_KEY))
                .as("过滤链结束后 MDC 不能残留")
                .isNull();
    }

    @Test
    @DisplayName("异常路径同样清理 MDC")
    void clearsMdcOnException() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/health");
        req.addHeader(TraceIdFilter.HEADER, "trace-boom");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain boom = (rq, rs) -> {
            throw new IllegalStateException("boom");
        };

        try {
            filter.doFilter(req, resp, boom);
        } catch (Exception ignored) {
            // 预期抛出
        }
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("两个连续请求不共享 traceId（线程复用污染检查）")
    void noCrossRequestPollution() throws Exception {
        String first = runWithHeader(null).getHeader(TraceIdFilter.HEADER);
        String second = runWithHeader(null).getHeader(TraceIdFilter.HEADER);
        assertThat(first).isNotEqualTo(second);
    }
}
