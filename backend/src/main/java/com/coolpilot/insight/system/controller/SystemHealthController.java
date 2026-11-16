package com.coolpilot.insight.system.controller;

import com.coolpilot.insight.common.security.RequireRole;
import com.coolpilot.insight.common.web.Result;
import com.coolpilot.insight.system.application.LayeredHealthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 分层健康检查（SOURCE-05）。
 *
 * <p>与 {@code /api/health} 的分工：
 * <ul>
 *   <li>{@code /api/health} 给容器健康检查用，**免鉴权**，只回 进程 + DB；</li>
 *   <li>本接口给运维排查用，**要求登录**，逐个依赖层给出状态、耗时与原因。</li>
 * </ul>
 * 分开的理由：容器探活不能带 Token，而分层结果包含内部服务地址与错误细节，
 * 不适合公开。
 */
@RestController
@RequestMapping("/api/system")
public class SystemHealthController {

    private final LayeredHealthService healthService;

    public SystemHealthController(LayeredHealthService healthService) {
        this.healthService = healthService;
    }

    @RequireRole
    @GetMapping("/health/layers")
    public Result<Map<String, Object>> layers() {
        return Result.ok(healthService.check());
    }
}
