package com.coolpilot.insight.integration;

import com.coolpilot.insight.common.exception.IntegrationException;
import com.coolpilot.insight.integration.common.CommandResult;
import com.coolpilot.insight.integration.common.ProcessRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 子进程执行器（R3-03 抽出的统一出口）。
 *
 * <p>不启动 Spring 容器：本类无依赖，直接 new 即可，跑得快。
 * 用 {@code java} 自身作为被测命令——测试环境必然存在，不需要额外依赖。
 */
class ProcessRunnerTest {

    private final ProcessRunner runner = new ProcessRunner();

    @Test
    @DisplayName("正常命令返回 exit=0 并捕获输出")
    void successCapturesOutput() {
        CommandResult r = runner.run(List.of("java", "-version"), null, Map.of(), 30);

        assertThat(r.success()).isTrue();
        assertThat(r.exitCode()).isZero();
        assertThat(r.output()).as("stdout 与 stderr 已合并").containsIgnoringCase("version");
        assertThat(r.durationMs()).isPositive();
    }

    @Test
    @DisplayName("非零退出码不抛异常，由调用方判断成功与否")
    void nonZeroExitIsReturned() {
        CommandResult r = runner.run(List.of("java", "-not-a-real-flag"), null, Map.of(), 30);

        assertThat(r.success()).isFalse();
        assertThat(r.exitCode()).isNotZero();
    }

    @Test
    @DisplayName("命令不存在时抛 IntegrationException（502 语义）")
    void missingBinaryThrows() {
        assertThatThrownBy(() ->
                runner.run(List.of("definitely-not-a-command-xyz"), null, Map.of(), 10))
                .isInstanceOf(IntegrationException.class)
                .hasMessageContaining("无法启动外部进程");
    }

    @Test
    @DisplayName("超时被强制终止并抛 IntegrationException，而不是永久挂起")
    void timeoutKillsProcess() {
        long start = System.currentTimeMillis();
        assertThatThrownBy(() ->
                runner.run(List.of("java", "-version"), null, Map.of(), 0))
                .isInstanceOf(IntegrationException.class)
                .hasMessageContaining("超时");

        long elapsed = System.currentTimeMillis() - start;
        assertThat(elapsed).as("必须在超时后立刻返回").isLessThan(10_000);
    }

    @Test
    @DisplayName("tail() 只保留尾部，避免超长输出刷屏")
    void tailTruncates() {
        CommandResult r = new CommandResult(1, "x".repeat(1000), 1);
        assertThat(r.tail(100)).hasSize(100);
        assertThat(new CommandResult(1, "abc", 1).tail(100)).isEqualTo("abc");
        assertThat(new CommandResult(1, null, 1).tail(100)).isEmpty();
    }
}
