package com.coolpilot.insight.integration.ai;

import com.coolpilot.insight.integration.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * 本地开发时拉起 Flask 服务。
 *
 * <p>从 {@code CoolPilotInsightApplication} 里搬出来：启动类不该知道 Python 解释器、
 * 脚本目录和子进程管理。容器部署时由 compose 独立启动，本类通过
 * {@code app.flask-autostart=false} 关闭。
 */
@Component
public class AiServiceLauncher {

    private static final Logger log = LoggerFactory.getLogger(AiServiceLauncher.class);

    private final AppProperties props;
    private Process process;

    public AiServiceLauncher(AppProperties props) {
        this.props = props;
    }

    /** @return 是否成功拉起（已配置为外部服务时不拉起，返回 false） */
    public boolean startIfConfigured(boolean autostart) {
        if (!autostart) {
            log.info("[Flask] 使用外部 AI 服务，不自动拉起本地进程");
            return false;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(props.getPythonExe(), "server.py");
            pb.directory(new File(props.getPythonCleanDir()));
            pb.redirectErrorStream(true);
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            process = pb.start();
            Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
            log.info("[Flask] 分词服务已启动");
            return true;
        } catch (Exception e) {
            // 拉起失败不阻塞主应用：词云与 AI 话题分析降级，其余功能正常
            log.warn("[Flask] 启动失败，词云将不可用: {}", e.getMessage());
            return false;
        }
    }

    public void stop() {
        if (process != null && process.isAlive()) {
            process.destroy();
            log.info("[Flask] 分词服务已关闭");
        }
    }
}
