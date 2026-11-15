package com.coolpilot.insight.system.menu;

import com.coolpilot.insight.AbstractIntegrationTest;
import com.coolpilot.insight.common.exception.BizException;
import com.coolpilot.insight.common.exception.ErrorCode;
import com.coolpilot.insight.common.security.RequestContext;
import com.coolpilot.insight.system.application.PermissionQueryService;
import com.coolpilot.insight.system.application.UserService;
import com.coolpilot.insight.system.application.menu.MenuService;
import com.coolpilot.insight.system.domain.menu.Menu;
import com.coolpilot.insight.system.dto.menu.MenuNode;
import com.coolpilot.insight.system.infrastructure.persistence.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 菜单树（Batch 2）。重点：树结构正确、按权限过滤、有子级不可删。 */
class MenuServiceTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(1);

    @Autowired
    private MenuService menuService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PermissionQueryService permissionQueryService;

    @AfterEach
    void clear() {
        RequestContext.clear();
        permissionQueryService.invalidate();
    }

    private void loginAdmin() {
        RequestContext.setUserId(1L);
        RequestContext.setRole(1);
    }

    @Test
    @DisplayName("全量树：7 个一级分组，且含子节点")
    void fullTreeHasGroups() {
        loginAdmin();
        List<MenuNode> tree = menuService.fullTree();

        assertThat(tree).hasSize(7);
        MenuNode workbench = tree.stream().filter(n -> "工作台".equals(n.getName()))
                .findFirst().orElseThrow();
        assertThat(workbench.getChildren()).isNotEmpty();
    }

    @Test
    @DisplayName("普通用户看不到需要权限的菜单（系统管理下的配置项）")
    void currentTreeFiltersByPermission() {
        String phone = "188" + String.format("%08d", 70_000_000 + SEQ.getAndIncrement());
        userService.register(phone, "Passw0rd!1234", "Passw0rd!1234");
        Long userId = userMapper.selectByPhone(phone).getId();

        RequestContext.setUserId(userId);
        RequestContext.setRole(0);
        List<MenuNode> tree = menuService.currentTree();

        List<String> names = tree.stream().flatMap(n -> n.getChildren().stream())
                .map(MenuNode::getName).toList();
        assertThat(names).doesNotContain("字典管理", "操作审计");
        // 无权限要求的菜单仍可见
        assertThat(names).contains("获客驾驶舱");
    }

    @Test
    @DisplayName("管理员可见全部菜单")
    void adminSeesAll() {
        loginAdmin();
        List<String> names = menuService.currentTree().stream()
                .flatMap(n -> n.getChildren().stream()).map(MenuNode::getName).toList();
        assertThat(names).contains("字典管理", "操作审计", "角色管理");
    }

    @Test
    @DisplayName("父级不存在时拒绝创建")
    void invalidParentRejected() {
        loginAdmin();
        Menu m = new Menu();
        m.setName("孤儿菜单");
        m.setParentId(999_999L);

        assertThatThrownBy(() -> menuService.create(m))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("有子菜单时不可删除，避免孤儿节点")
    void cannotDeleteMenuWithChildren() {
        loginAdmin();
        List<MenuNode> tree = menuService.fullTree();
        MenuNode group = tree.stream().filter(n -> "工作台".equals(n.getName()))
                .findFirst().orElseThrow();

        assertThatThrownBy(() -> menuService.delete(group.getId()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.STATE_CONFLICT);
    }

    @Test
    @DisplayName("非法状态值被拒")
    void invalidStatusRejected() {
        loginAdmin();
        assertThatThrownBy(() -> menuService.setStatus(1L, "WHATEVER"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }
}
