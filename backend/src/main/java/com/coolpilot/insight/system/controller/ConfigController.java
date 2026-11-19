package com.coolpilot.insight.system.controller;

import com.coolpilot.insight.common.security.RequireRole;
import com.coolpilot.insight.common.web.Result;
import com.coolpilot.insight.system.application.ConfigAdminService;
import com.coolpilot.insight.system.dto.ConfigItemView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 系统配置管理（仅管理员）。
 *
 * <p>路径采用 R1-06 的版本化规范：新接口直接用 {@code /api/v1}，
 * 存量接口在 R4 统一迁移，避免新接口再返工一次。
 */
@RestController
@RequestMapping("/api/v1/system/configs")
public class ConfigController {

    private final ConfigAdminService configAdminService;

    public ConfigController(ConfigAdminService configAdminService) {
        this.configAdminService = configAdminService;
    }

    @RequireRole(role = 1)
    @GetMapping
    public Result<List<ConfigItemView>> list() {
        return Result.ok(configAdminService.list());
    }

    @RequireRole(role = 1)
    @PutMapping("/{key}")
    public Result<ConfigItemView> update(@PathVariable String key,
                                         @RequestBody Map<String, String> body) {
        return Result.ok(configAdminService.update(key, body.get("value")));
    }
}
