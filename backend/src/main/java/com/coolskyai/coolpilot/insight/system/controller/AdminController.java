package com.coolskyai.coolpilot.insight.system.controller;

import com.coolskyai.coolpilot.insight.common.security.RequireRole;
import com.coolskyai.coolpilot.insight.common.web.PageResult;
import com.coolskyai.coolpilot.insight.common.web.Result;
import com.coolskyai.coolpilot.insight.system.application.UserAdminService;
import com.coolskyai.coolpilot.insight.system.dto.UserView;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户管理接口（仅管理员）。
 *
 * <p>本类只做参数接收与结果返回。分页、脱敏、登录态清理等规则在
 * {@link UserAdminService}，不在此处直接访问 Mapper。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final UserAdminService userAdminService;

    public AdminController(UserAdminService userAdminService) {
        this.userAdminService = userAdminService;
    }

    @RequireRole(role = 1)
    @GetMapping("/users")
    public Result<PageResult<UserView>> listUsers(@RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        return Result.ok(userAdminService.list(page, size));
    }

    @RequireRole(role = 1)
    @DeleteMapping("/user/{id}")
    public Result<String> deleteUser(@PathVariable Long id) {
        boolean removed = userAdminService.delete(id);
        return Result.ok(removed ? "删除成功" : "用户不存在");
    }
}
