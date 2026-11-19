package com.coolpilot.insight.source.controller;

import com.coolpilot.insight.common.security.RequireRole;
import com.coolpilot.insight.common.web.Result;
import com.coolpilot.insight.source.application.AccountVerificationService;
import com.coolpilot.insight.source.application.SourceAccountService;
import com.coolpilot.insight.source.domain.SourceAccount;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 平台账号接口。
 *
 * <p>权限暂用 {@code @RequireRole}：细分权限码（source:account:*）需先补
 * sys_permission 数据，属权限初始化改动，留待专门批次。
 *
 * <p>**安全边界**：本控制器不暴露任何写 auth_status 的入口，
 * 请求体里的 authStatus 字段会被忽略（Service 不读取它）。
 */
@RestController
@RequestMapping("/api/v1/source/accounts")
public class SourceAccountController {

    private final SourceAccountService service;
    private final AccountVerificationService verificationService;

    public SourceAccountController(SourceAccountService service,
                                   AccountVerificationService verificationService) {
        this.verificationService = verificationService;
        this.service = service;
    }

    @RequireRole(role = 0)
    @GetMapping
    public Result<List<SourceAccount>> list() {
        return Result.ok(service.list());
    }

    @RequireRole(role = 0)
    @GetMapping("/{id}")
    public Result<SourceAccount> detail(@PathVariable Long id) {
        return Result.ok(service.detail(id));
    }

    @RequireRole(role = 1)
    @PostMapping
    public Result<SourceAccount> create(@RequestBody SourceAccount in) {
        return Result.ok(service.create(in));
    }

    @RequireRole(role = 1)
    @PutMapping("/{id}")
    public Result<SourceAccount> update(@PathVariable Long id, @RequestBody SourceAccount in) {
        return Result.ok(service.updateProfile(id, in));
    }

    @RequireRole(role = 1)
    @PatchMapping("/{id}/status")
    public Result<String> setStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        service.setStatus(id, body.get("status"));
        return Result.ok("已更新");
    }

    @RequireRole(role = 0)
    @GetMapping("/{id}/usage")
    public Result<Map<String, Object>> usage(@PathVariable Long id) {
        return Result.ok(service.usage(id));
    }

    /** 触发授权验证。返回 scope=CONFIG_ONLY，表示只确认凭证配置与可达性。 */
    @RequireRole(role = 1)
    @PostMapping("/{id}/verify")
    public Result<AccountVerificationService.VerifyResult> verify(@PathVariable Long id) {
        return Result.ok(verificationService.verify(id));
    }
}
