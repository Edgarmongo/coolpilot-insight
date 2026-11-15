package com.coolpilot.insight.system.controller;

import com.coolpilot.insight.common.security.RequireRole;
import com.coolpilot.insight.common.web.PageResult;
import com.coolpilot.insight.common.web.Result;
import com.coolpilot.insight.system.application.AuditQueryService;
import com.coolpilot.insight.system.dto.AuditLogView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 操作审计查询（仅管理员，只读）。 */
@RestController
@RequestMapping("/api/v1/system/audit-logs")
public class AuditLogController {

    private final AuditQueryService auditQueryService;

    public AuditLogController(AuditQueryService auditQueryService) {
        this.auditQueryService = auditQueryService;
    }

    @RequireRole(role = 1)
    @GetMapping
    public Result<PageResult<AuditLogView>> page(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(auditQueryService.page(action, userId, page, size));
    }
}
