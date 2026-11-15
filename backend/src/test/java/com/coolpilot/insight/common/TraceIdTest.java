package com.coolpilot.insight.common;

import com.coolpilot.insight.common.web.Result;
import com.coolpilot.insight.common.web.TraceIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trace ID（Batch 1 公共基础）。
 *
 * <p>重点是两件事：响应里真的带上 traceId；MDC 用完必须清理，
 * 否则 Tomcat 线程复用时会把上一个请求的 traceId 带到下一个请求上。
 */
class TraceIdTest {

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    @DisplayName("有上下文时响应携带 traceId")
    void resultCarriesTraceId() {
        MDC.put(TraceIdFilter.MDC_KEY, "abc123");

        assertThat(Result.ok("x").getTraceId()).isEqualTo("abc123");
        assertThat(Result.fail(40400, "not found").getTraceId()).isEqualTo("abc123");
    }

    @Test
    @DisplayName("无上下文时 traceId 为 null，而不是抛异常")
    void noContextIsAllowed() {
        MDC.clear();
        assertThat(Result.ok("x").getTraceId()).isNull();
    }

    @Test
    @DisplayName("current() 与 MDC 一致，且清理后为空")
    void currentFollowsMdc() {
        MDC.put(TraceIdFilter.MDC_KEY, "t-1");
        assertThat(TraceIdFilter.current()).isEqualTo("t-1");

        MDC.remove(TraceIdFilter.MDC_KEY);
        assertThat(TraceIdFilter.current()).isNull();
    }

    @Test
    @DisplayName("成功与失败响应结构一致（都含 code/message/data/traceId）")
    void shapeIsConsistent() {
        MDC.put(TraceIdFilter.MDC_KEY, "shape");
        Result<String> ok = Result.ok("data");
        Result<String> fail = Result.fail(40001, "bad");

        assertThat(ok.getCode()).isEqualTo(200);
        assertThat(fail.getCode()).isEqualTo(40001);
        assertThat(ok.getTraceId()).isEqualTo("shape");
        assertThat(fail.getTraceId()).isEqualTo("shape");
    }
}
