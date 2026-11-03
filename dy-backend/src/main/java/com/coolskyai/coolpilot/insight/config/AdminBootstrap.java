package com.coolskyai.coolpilot.insight.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 管理员账户引导。
 *
 * <p>Flyway 种子写入的密码是哨兵值 {@code !SENTINEL_NO_LOGIN!}——即使数据库被拖走也无法登录。
 * 真实密码只来自环境变量 {@code ADMIN_INIT_PASSWORD}，由本组件在启动时写入。
 *
 * <p>幂等性通过 CAS 条件更新保证：{@code WHERE password_hash = '!SENTINEL_NO_LOGIN!'}，
 * 多实例并发启动时只有一个实例能更新成功（受影响行数 &gt; 0）。
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    private static final String SENTINEL = "!SENTINEL_NO_LOGIN!";

    private final JdbcTemplate jdbc;

    @Value("${app.admin-init-password:}")
    private String initPassword;

    public AdminBootstrap(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user WHERE password_hash = ?", Integer.class, SENTINEL);
        if (pending == null || pending == 0) {
            return;
        }
        if (initPassword == null || initPassword.trim().isEmpty()) {
            log.warn("[AdminBootstrap] 存在 {} 个哨兵管理员账号，但未提供 ADMIN_INIT_PASSWORD，"
                    + "这些账号当前不可登录。请在 .env 中设置后重启。", pending);
            return;
        }
        if (initPassword.length() < 8) {
            log.warn("[AdminBootstrap] ADMIN_INIT_PASSWORD 长度不足 8，已忽略。");
            return;
        }

        String hash = new BCryptPasswordEncoder().encode(initPassword);
        int updated = jdbc.update(
                "UPDATE user SET password_hash = ?, must_change_pwd = 1 WHERE password_hash = ?",
                hash, SENTINEL);
        if (updated > 0) {
            log.info("[AdminBootstrap] 已初始化 {} 个管理员账号，首次登录需修改密码。", updated);
        } else {
            log.info("[AdminBootstrap] 管理员密码已由其他实例初始化，跳过。");
        }
    }
}
