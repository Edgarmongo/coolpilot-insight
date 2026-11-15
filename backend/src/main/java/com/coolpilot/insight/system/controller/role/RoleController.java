package com.coolpilot.insight.system.controller.role;

import com.coolpilot.insight.common.security.RequirePermission;
import com.coolpilot.insight.common.security.RequireRole;
import com.coolpilot.insight.common.web.Result;
import com.coolpilot.insight.system.application.role.RoleCommandService;
import com.coolpilot.insight.system.application.role.RoleQueryService;
import com.coolpilot.insight.system.infrastructure.role.RoleMapper;
import com.coolpilot.insight.system.dto.role.RoleView;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 角色接口。
 *
 * <p>权限暂用 {@code @RequireRole(role=1)}（等价于管理员），
 * 待 {@code @RequirePermission} 落地后改为细粒度权限码——两者并存过渡，避免一次性改全部接口。
 */
@RestController
@RequestMapping("/api/v1/system/roles")
public class RoleController {

    private final RoleQueryService roleQueryService;
    private final RoleCommandService roleCommandService;
    private final RoleMapper roleMapper;

    public RoleController(RoleQueryService roleQueryService, RoleCommandService roleCommandService,
                          RoleMapper roleMapper) {
        this.roleQueryService = roleQueryService;
        this.roleCommandService = roleCommandService;
        this.roleMapper = roleMapper;
    }

    /** 该角色已授权的菜单 id（用于回显勾选状态）。 */
    @RequireRole(role = 0)
    @RequirePermission("system:role:list")
    @GetMapping("/{id}/menus")
    public Result<List<Long>> listMenuIds(@PathVariable Long id) {
        return Result.ok(roleMapper.selectMenuIds(id));
    }

    /** 覆盖式保存菜单授权。 */
    @RequireRole(role = 0)
    @RequirePermission("system:role:manage")
    @PutMapping("/{id}/menus")
    @SuppressWarnings("unchecked")
    public Result<String> grantMenus(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Object raw = body.get("menuIds");
        List<Long> ids = raw instanceof List<?> list
                ? list.stream().map(o -> Long.valueOf(String.valueOf(o))).toList()
                : List.of();
        roleCommandService.grantMenus(id, ids);
        return Result.ok("已保存");
    }

    @RequireRole(role = 0)
    @RequirePermission("system:role:list")
    @GetMapping
    public Result<List<RoleView>> list() {
        return Result.ok(roleQueryService.list());
    }

    @RequireRole(role = 1)
    @PostMapping
    public Result<RoleView> create(@RequestBody Map<String, String> body) {
        return Result.ok(roleCommandService.create(body.get("code"), body.get("name")));
    }

    @RequireRole(role = 1)
    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Long id) {
        roleCommandService.delete(id);
        return Result.ok("已删除");
    }

    @RequireRole(role = 1)
    @PostMapping("/{id}/users/{userId}")
    public Result<String> assign(@PathVariable Long id, @PathVariable Long userId) {
        roleCommandService.assignRole(userId, id);
        return Result.ok("已分配");
    }

    @RequireRole(role = 1)
    @DeleteMapping("/{id}/users/{userId}")
    public Result<String> revoke(@PathVariable Long id, @PathVariable Long userId) {
        roleCommandService.revokeRole(userId, id);
        return Result.ok("已解除");
    }
}
