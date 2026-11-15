package com.coolpilot.insight.integration.common;

import com.coolpilot.insight.common.exception.IntegrationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 子进程执行器。
 *
 * <p>把「起进程 / 捕获输出 / 超时 / 异常翻译」收在一处。此前这段逻辑在
 * CollectServiceImpl、DiscoveryService、CleanServiceImpl 各写了一遍，
 * 行为还不一致（有的没有超时，有的吞了异常）。
 *
 * <p>超时后强制杀进程并抛 {@link IntegrationException}（502），
 * 而不是让请求线程永远挂住——采集脚本卡死是常态而非异常。
 */
@Component
public class ProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(ProcessRunner.class);

    public CommandResult run(List<String> command, String workingDir, Map<String, String> env,
                             int timeoutSeconds) {
        long start = System.currentTimeMillis();
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workingDir != null && !workingDir.isBlank()) {
            pb.directory(new File(workingDir));
        }
        pb.redirectErrorStream(true);
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        if (env != null) {
            pb.environment().putAll(env);
        }

        Process process;
        try {
            process = pb.start();
        } catch (Exception e) {
            throw new IntegrationException("无法启动外部进程: " + command.get(0), e);
        }

        StringBuilder output = new StringBuilder();
        // 必须单独线程读：若进程卡住不输出，主线程的 readLine 会永远阻塞，超时就不生效了
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    output.append(line).append('\n');
                }
            } catch (Exception ignored) {
                // 进程被强杀时流会关闭，属预期
            }
        }, "proc-reader");
        reader.setDaemon(true);
        reader.start();

        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IntegrationException("外部调用被中断", e);
        }

        if (!finished) {
            process.destroyForcibly();
            throw new IntegrationException(
                    "外部调用超时(" + timeoutSeconds + "s): " + String.join(" ", command));
        }

        try {
            reader.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        int exit = process.exitValue();
        long cost = System.currentTimeMillis() - start;
        if (exit != 0) {
            log.warn("[proc] 退出码 {} 耗时 {}ms cmd={} 输出尾部: {}",
                    exit, cost, command.get(0), tailOf(output.toString()));
        }
        return new CommandResult(exit, output.toString(), cost);
    }

    private static String tailOf(String s) {
        return s.length() <= 500 ? s : s.substring(s.length() - 500);
    }
}
