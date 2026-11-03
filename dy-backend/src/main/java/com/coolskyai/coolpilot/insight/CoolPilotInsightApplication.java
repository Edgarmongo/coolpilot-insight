package com.coolskyai.coolpilot.insight;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.File;

@SpringBootApplication
@MapperScan("com.coolskyai.coolpilot.insight.mapper")
public class CoolPilotInsightApplication {
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(CoolPilotInsightApplication.class, args);

        // 本地开发：自动拉起 Flask 分词服务；容器部署：由独立 flask 服务承担
        if (!"false".equalsIgnoreCase(context.getEnvironment().getProperty("app.flask-autostart", "true"))) {
            startFlaskService(context);
        }

        System.out.println("==============================================");
        System.out.println("        启动成功！http://localhost:8080");
        System.out.println("        Swagger: http://localhost:8080/swagger-ui.html");
        System.out.println("==============================================");
    }

    /**
     * 启动 Flask 分词服务（词云 + AI 话题分析）。
     * Python 解释器与脚本目录均来自配置，兼容 Windows 本地与 Linux 容器。
     */
    private static void startFlaskService(ConfigurableApplicationContext context) {
        Process flaskProcess = null;
        try {
            String pythonExe = context.getEnvironment().getProperty("app.python-exe", "python");
            String scriptDir = context.getEnvironment().getProperty("app.python-clean-dir", "../Py_Data/clean");
            ProcessBuilder pb = new ProcessBuilder(pythonExe, "wordcloud_service.py");
            pb.directory(new File(scriptDir));
            pb.redirectErrorStream(true);
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            flaskProcess = pb.start();
            System.out.println("[Flask] 分词服务已启动");
        } catch (Exception e) {
            System.out.println("[Flask] 分词服务启动失败，词云将不可用: " + e.getMessage());
            return;
        }

        final Process fp = flaskProcess;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (fp != null && fp.isAlive()) {
                fp.destroy();
                System.out.println("[Flask] 分词服务已关闭");
            }
        }));
    }
}
