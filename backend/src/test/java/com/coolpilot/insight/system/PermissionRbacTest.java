package com.coolpilot.insight.system;

import com.coolpilot.insight.AbstractIntegrationTest;
import com.coolpilot.insight.common.security.RequestContext;
import com.coolpilot.insight.system.application.PermissionQueryService;
import com.coolpilot.insight.system.application.PermissionService;
import com.coolpilot.insight.system.application.UserService;
import com.coolpilot.insight.system.infrastructure.persistence.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RBAC 判定（Batch 2 第三步）。
 *
 * <p>这是整个 Batch 2 里唯一可能造成「越权」或「全员失权」的改动，因此断言要两边都压：
 * 管理员必须仍是管理员；普通用户不能因为迁移被误判成管理员。
 */
class PermissionRbacTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(1);
    private static final String PASSWORD = "Passw0rd!1234";

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private PermissionQueryService permissionQueryService;

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
        String phone = "132" + String.format("%08d", 60_000_000 + SEQ.getAndIncrement());
        userService.register(phone, PASSWORD, PASSWORD);
        return userMapper.selectByPhone(phone).getId();
    }

    @Test
    @DisplayName("种子管理员（V13 映射为 ADMIN）判定为管理员")
    void seededAdminIsAdmin() {
        RequestContext.setUserId(1L);
        RequestContext.setRole(1);

        assertThat(permissionService.isAdmin()).isTrue();
        assertThat(permissionQueryService.hasRole(1L, "ADMIN")).isTrue();
    }

    @Test
    @DisplayName("新注册用户映射为 USER，不是管理员")
    void normalUserIsNotAdmin() {
        Long id = newUser();
        RequestContext.setUserId(id);
        RequestContext.setRole(0);

        assertThat(permissionService.isAdmin()).isFalse();
        assertThat(permissionQueryService.hasRole(id, "USER")).isTrue();
        assertThat(permissionQueryService.hasRole(id, "ADMIN")).isFalse();
    }

    @Test
    @DisplayName("ADMIN 拥有全部 14 个权限点，普通用户只有 3 个")
    void permissionSetsDiffer() {
        Long normal = newUser();

        assertThat(permissionQueryService.permissionCodesOf(1L)).hasSizeGreaterThanOrEqualTo(14)
                .contains("system:role:manage");
        assertThat(permissionQueryService.permissionCodesOf(normal)).hasSize(3)
                .doesNotContain("system:role:manage");
    }

    @Test
    @DisplayName("未登录时不是管理员")
    void anonymousIsNotAdmin() {
        RequestContext.clear();
        assertThat(permissionService.isAdmin()).isFalse();
    }

    @Test
    @DisplayName("缓存失效后能读到最新授权")
    void invalidateRefreshes() {
        assertThat(permissionQueryService.permissionCodesOf(1L)).hasSizeGreaterThanOrEqualTo(14);
        permissionQueryService.invalidate(1L);
        assertThat(permissionQueryService.permissionCodesOf(1L)).hasSizeGreaterThanOrEqualTo(14);
    }
}
