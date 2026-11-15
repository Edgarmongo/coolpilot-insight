package com.coolpilot.insight.system.controller.permission;

import com.coolpilot.insight.common.security.RequireRole;
import com.coolpilot.insight.common.web.Result;
import com.coolpilot.insight.system.application.PermissionQueryService;
import com.coolpilot.insight.system.application.PermissionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 当前用户的权限集合。
 *
 * <p>供前端控制按钮显隐。**这只是体验优化**——真正的拦截在每个接口的
 * {@code @RequirePermission} 上，改前端状态绕不过后端校验。
 */
@RestController
@RequestMapping("/api/v1/system/permissions")
public class PermissionController {

    private final PermissionService permissionService;
    private final PermissionQueryService permissionQueryService;

    public PermissionController(PermissionService permissionService,
                                PermissionQueryService permissionQueryService) {
        this.permissionService = permissionService;
        this.permissionQueryService = permissionQueryService;
    }

    @RequireRole(role = 0)
    @GetMapping("/mine")
    public Result<Map<String, Object>> mine() {
        Long userId = permissionService.currentUserId();
        return Result.ok(Map.of(
                "admin", permissionService.isAdmin(),
                "codes", List.copyOf(permissionQueryService.permissionCodesOf(userId))
        ));
    }
}
