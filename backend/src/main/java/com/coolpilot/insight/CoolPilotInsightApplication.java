package com.coolpilot.insight;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import com.coolpilot.insight.integration.ai.AiServiceLauncher;
import org.springframework.scheduling.annotation.EnableScheduling;


@SpringBootApplication
@EnableScheduling
@MapperScan({
        "com.coolpilot.insight.mapper",
        "com.coolpilot.insight.system.infrastructure.persistence",
        "com.coolpilot.insight.source.infrastructure.persistence",
        "com.coolpilot.insight.collection.infrastructure.persistence",
        "com.coolpilot.insight.content.infrastructure.persistence"
})
public class CoolPilotInsightApplication {
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(CoolPilotInsightApplication.class, args);

        // 本地开发：按配置自动拉起 Flask；容器部署由独立 flask 服务承担（autostart=false）
        boolean autostart = Boolean.parseBoolean(
                context.getEnvironment().getProperty("app.flask-autostart", "false"));
        context.getBean(AiServiceLauncher.class).startIfConfigured(autostart);

        System.out.println("==============================================");
        System.out.println("        启动成功！http://localhost:8080");
        System.out.println("        Swagger: http://localhost:8080/swagger-ui.html");
        System.out.println("==============================================");
    }
}
