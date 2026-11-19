package com.coolpilot.insight.system;

import com.coolpilot.insight.AbstractIntegrationTest;
import com.coolpilot.insight.common.security.RequestContext;
import com.coolpilot.insight.system.application.PermissionQueryService;
import com.coolpilot.insight.system.application.UserService;
import com.coolpilot.insight.system.infrastructure.persistence.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 角色-菜单授权（B 方案）。
 *
 * <p>核心断言：从「菜单授权」得到的权限码，必须与从「权限点」得到的合并成同一份集合——
 * 两条路径的结果不能分叉，否则会出现"菜单能看见但接口 403"。
 */
class RoleMenuGrantTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(1);

    @Autowired
    private PermissionQueryService permissionQueryService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserService userService;

    @Autowired
    private UserMapper userMapper;

    @AfterEach
    void clear() {
        RequestContext.clear();
        permissionQueryService.invalidate();
    }

    private Long newUser() {
        String phone = "189" + String.format("%08d", 80_000_000 + SEQ.getAndIncrement());
        userService.register(phone, "Passw0rd!1234", "Passw0rd!1234");
        return userMapper.selectByPhone(phone).getId();
    }

    @Test
    @DisplayName("回填后管理员仍拥有全部 14 个权限（切换未减少授权）")
    void backfillKeepsAdminPermissions() {
        Set<String> perms = permissionQueryService.permissionCodesOf(1L);
        assertThat(perms).hasSizeGreaterThanOrEqualTo(14).contains("system:role:list", "system:dict:manage");
    }

    @Test
    @DisplayName("仅给角色菜单授权即可获得该权限码（不依赖 sys_role_permission）")
    void menuGrantAloneGrantsPermission() {
        Long userId = newUser();
        Long roleId = jdbc.queryForObject(
                "SELECT role_id FROM sys_user_role WHERE user_id = ?", Long.class, userId);
        assertThat(permissionQueryService.permissionCodesOf(userId))
                .doesNotContain("system:role:list");

        // 只加 role_menu，不加 role_permission
        Long menuId = jdbc.queryForObject(
                "SELECT id FROM sys_menu WHERE permission_code = 'system:role:list'", Long.class);
        jdbc.update("INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, ?)", roleId, menuId);
        permissionQueryService.invalidate();

        assertThat(permissionQueryService.permissionCodesOf(userId))
                .as("菜单授权应直接产生权限码")
                .contains("system:role:list");

        jdbc.update("DELETE FROM sys_role_menu WHERE role_id = ? AND menu_id = ?", roleId, menuId);
        permissionQueryService.invalidate();
    }

    @Test
    @DisplayName("无权限码的菜单（登录可见）不参与授权判定")
    void menuWithoutPermissionCodeIsIgnored() {
        // '获客驾驶舱' 的 permission_code 为空，不应出现在任何人的权限集合里
        Long id = jdbc.queryForObject(
                "SELECT id FROM sys_menu WHERE path = '/dashboard'", Long.class);
        String code = jdbc.queryForObject(
                "SELECT permission_code FROM sys_menu WHERE id = ?", String.class, id);
        assertThat(code).isNull();
        assertThat(permissionQueryService.permissionCodesOf(1L)).allMatch(p -> !p.isBlank());
    }
}
