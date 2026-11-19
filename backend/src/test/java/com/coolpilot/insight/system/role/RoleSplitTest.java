package com.coolpilot.insight.system.role;

import com.coolpilot.insight.AbstractIntegrationTest;
import com.coolpilot.insight.common.exception.BizException;
import com.coolpilot.insight.common.exception.ErrorCode;
import com.coolpilot.insight.system.application.UserService;
import com.coolpilot.insight.system.application.role.RoleCommandService;
import com.coolpilot.insight.system.application.role.RoleQueryService;
import com.coolpilot.insight.system.dto.role.RoleView;
import com.coolpilot.insight.system.infrastructure.persistence.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 角色查询/命令分离后的行为基线（原 RoleService 拆为 Query + Command）。 */
class RoleSplitTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(1);
    private static final String PASSWORD = "Passw0rd!1234";

    @Autowired
    private RoleQueryService roleQueryService;

    @Autowired
    private RoleCommandService roleCommandService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserMapper userMapper;

    private Long newUser() {
        String phone = "133" + String.format("%08d", 50_000_000 + SEQ.getAndIncrement());
        userService.register(phone, PASSWORD, PASSWORD);
        return userMapper.selectByPhone(phone).getId();
    }

    @Test
    @DisplayName("种子角色存在且 ADMIN 为内置")
    void seedsPresent() {
        List<RoleView> roles = roleQueryService.list();
        assertThat(roles).extracting(RoleView::getCode)
                .contains("ADMIN", "USER", "REVIEWER");
        assertThat(roles).filteredOn(r -> "ADMIN".equals(r.getCode()))
                .allMatch(RoleView::isBuiltin);
    }

    @Test
    @DisplayName("内置角色不可删除")
    void builtinCannotBeDeleted() {
        RoleView admin = roleQueryService.list().stream()
                .filter(r -> "ADMIN".equals(r.getCode())).findFirst().orElseThrow();

        assertThatThrownBy(() -> roleCommandService.delete(admin.getId()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("新建角色：编码重复被拒")
    void duplicateCodeRejected() {
        roleCommandService.create("TESTROLE", "测试角色");
        assertThatThrownBy(() -> roleCommandService.create("TESTROLE", "另一个"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE);
    }

    @Test
    @DisplayName("新建角色：编码格式非法被拒")
    void invalidCodeRejected() {
        assertThatThrownBy(() -> roleCommandService.create("bad code", "x"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("角色仍有用户关联时不可删除")
    void roleWithUsersCannotBeDeleted() {
        RoleView role = roleCommandService.create("TMPROLE", "临时角色");
        roleCommandService.assignRole(newUser(), role.getId());

        assertThatThrownBy(() -> roleCommandService.delete(role.getId()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.STATE_CONFLICT);
    }

    @Test
    @DisplayName("分配与解除角色")
    void assignAndRevoke() {
        Long userId = newUser();
        RoleView role = roleCommandService.create("TMPROLE2", "临时角色2");

        roleCommandService.assignRole(userId, role.getId());
        assertThat(roleQueryService.rolesOf(userId)).contains("TMPROLE2");

        roleCommandService.revokeRole(userId, role.getId());
        assertThat(roleQueryService.rolesOf(userId)).doesNotContain("TMPROLE2");
    }
}
