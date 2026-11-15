package com.coolskyai.coolpilot.insight;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

/**
 * 集成测试基类：一个真实 MySQL 容器 + 完整 Spring 上下文。
 *
 * <p>用 Testcontainers 而不是 H2，是因为迁移脚本用了 MySQL 专有语法
 * （utf8mb4、CHECK 约束、ON UPDATE CURRENT_TIMESTAMP、JSON 列），H2 跑不过 Flyway。
 *
 * <p>容器在类加载时启动、整个 JVM 只起一次，并且**不随测试类结束而停止**。
 * 这一点很关键：Spring 的上下文缓存按测试类区分，若交给 JUnit 的 {@code @Container}
 * 管理生命周期，第二个测试类会另起容器，而缓存的上下文仍指向旧端口，
 * 表现为 {@code Communications link failure}。故这里手动 start，用 DynamicPropertySource 注入连接信息。
 *
 * <p>各测试类之间靠「各造各的数据」隔离，不依赖执行顺序。
 *
 * <p>jwt.secret 必须显式给出：应用配置里是 ${JWT_SECRET:}，空值会导致启动失败。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "jwt.secret=test-only-secret-0123456789abcdef0123456789abcdef",
                "app.admin-init-password=",
                "mybatis.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl"
        })
public abstract class AbstractIntegrationTest {

    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("coolpilot_insight")
                    .withUsername("root")
                    .withPassword("root")
                    .withCommand("--character-set-server=utf8mb4",
                                 "--collation-server=utf8mb4_unicode_ci");

    static {
        // 只 start 不 stop：容器生命周期与 JVM 对齐，进程退出时由 Ryuk 回收
        MYSQL.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }
}
