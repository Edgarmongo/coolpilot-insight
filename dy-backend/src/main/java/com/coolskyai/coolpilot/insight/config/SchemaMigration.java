package com.coolskyai.coolpilot.insight.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动时自动补齐数据库字段（幂等迁移）。
 *
 * <p>项目迭代过程中会给表加新字段，若只更新建表脚本，已有数据库不会自动升级。
 * 这里在应用启动时检查并补齐缺失的列，避免用户手动执行 SQL。</p>
 */
@Slf4j
@Component
public class SchemaMigration implements ApplicationRunner {

    @Value("${spring.datasource.url}")
    private String jdbcUrl;

    @Value("${spring.datasource.username}")
    private String username;

    @Value("${spring.datasource.password}")
    private String password;

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password)) {
            List<String> added = new ArrayList<>();

            addColumnIfMissing(conn, "comment", "douyin_id",
                    "`douyin_id` VARCHAR(64) NULL COMMENT '评论者抖音号(unique_id)'", added);
            addColumnIfMissing(conn, "comment", "douyin_uid",
                    "`douyin_uid` VARCHAR(32) NULL COMMENT '评论者抖音UID'", added);
            addColumnIfMissing(conn, "comment", "sec_uid",
                    "`sec_uid` VARCHAR(128) NULL COMMENT '评论者安全UID(主页链接用)'", added);

            if (!added.isEmpty()) {
                log.info("[Schema] 已自动补齐字段: {}", String.join(", ", added));
            }
        } catch (Exception e) {
            // 迁移失败不应阻断应用启动（例如数据库尚未就绪）
            log.warn("[Schema] 自动迁移跳过: {}", e.getMessage());
        }
    }

    private void addColumnIfMissing(Connection conn, String table, String column,
                                     String columnDef, List<String> added) {
        try {
            if (columnExists(conn, table, column)) {
                return;
            }
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("ALTER TABLE `" + table + "` ADD COLUMN " + columnDef);
                added.add(table + "." + column);
            }
        } catch (Exception e) {
            log.warn("[Schema] 添加 {}.{} 失败: {}", table, column, e.getMessage());
        }
    }

    private boolean columnExists(Connection conn, String table, String column) {
        try {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getColumns(conn.getCatalog(), null, table, column)) {
                return rs.next();
            }
        } catch (Exception e) {
            return false;
        }
    }
}
