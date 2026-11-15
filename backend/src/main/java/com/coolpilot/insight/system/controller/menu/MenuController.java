package com.coolpilot.insight.system.controller.menu;

import com.coolpilot.insight.common.security.RequirePermission;
import com.coolpilot.insight.common.security.RequireRole;
import com.coolpilot.insight.common.web.Result;
import com.coolpilot.insight.system.application.menu.MenuService;
import com.coolpilot.insight.system.domain.menu.Menu;
import com.coolpilot.insight.system.dto.menu.MenuNode;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 菜单接口。current 供前端构建导航；tree 供管理页配置。 */
@RestController
@RequestMapping("/api/v1/system/menus")
public class MenuController {

    private final MenuService menuService;

    public MenuController(MenuService menuService) {
        this.menuService = menuService;
    }

    @RequireRole(role = 0)
    @GetMapping("/current")
    public Result<List<MenuNode>> current() {
        return Result.ok(menuService.currentTree());
    }

    @RequireRole(role = 0)
    @RequirePermission("system:menu:list")
    @GetMapping("/tree")
    public Result<List<MenuNode>> tree() {
        return Result.ok(menuService.fullTree());
    }

    @RequireRole(role = 0)
    @RequirePermission("system:menu:manage")
    @PostMapping
    public Result<Menu> create(@RequestBody Menu menu) {
        return Result.ok(menuService.create(menu));
    }

    @RequireRole(role = 0)
    @RequirePermission("system:menu:manage")
    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Long id) {
        menuService.delete(id);
        return Result.ok("已删除");
    }
}
