package com.coolskyai.coolpilot.insight.controller;

import com.coolskyai.coolpilot.insight.dto.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查（免鉴权）。
 *
 * <p>刻意不加 {@code @RequireRole}：容器健康检查无法携带用户 Token。
 * 该端点只暴露进程与数据库连通性，不返回任何业务数据。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    @Autowired
    private JdbcTemplate jdbc;

    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "UP");
        try {
            Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
            out.put("db", one != null && one == 1 ? "UP" : "DOWN");
        } catch (Exception e) {
            out.put("db", "DOWN");
            out.put("dbError", e.getClass().getSimpleName());
        }
        return Result.ok(out);
    }
}
