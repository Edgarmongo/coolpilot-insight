package com.coolpilot.insight.system.application.menu;

import com.coolpilot.insight.common.exception.BizException;
import com.coolpilot.insight.common.exception.ErrorCode;
import com.coolpilot.insight.system.application.PermissionQueryService;
import com.coolpilot.insight.system.application.PermissionService;
import com.coolpilot.insight.system.domain.menu.Menu;
import com.coolpilot.insight.system.dto.menu.MenuNode;
import com.coolpilot.insight.system.infrastructure.menu.MenuMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 菜单查询与维护。
 *
 * <p>两条规则：
 * 1. **树不能有孤儿与环**：父级必须存在，且不能把节点挂到自己的子孙下；
 * 2. **隐藏菜单不等于禁止访问**：菜单只负责显示，权限由 {@code @RequirePermission} 守。
 */
@Service
public class MenuService {

    private final MenuMapper menuMapper;
    private final PermissionService permissionService;
    private final PermissionQueryService permissionQueryService;

    public MenuService(MenuMapper menuMapper, PermissionService permissionService,
                       PermissionQueryService permissionQueryService) {
        this.menuMapper = menuMapper;
        this.permissionService = permissionService;
        this.permissionQueryService = permissionQueryService;
    }

    /** 全量树（管理员配置用）。 */
    public List<MenuNode> fullTree() {
        return build(menuMapper.selectAll());
    }

    /** 当前用户可见树：管理员全量；其他人过滤掉无权访问的菜单。 */
    public List<MenuNode> currentTree() {
        List<Menu> all = menuMapper.selectVisible();
        if (!permissionService.isAdmin()) {
            Long userId = permissionService.currentUserId();
            Set<String> perms = permissionQueryService.permissionCodesOf(userId);
            all = all.stream()
                    .filter(m -> m.getPermissionCode() == null || m.getPermissionCode().isBlank()
                            || perms.contains(m.getPermissionCode()))
                    .toList();
        }
        return build(all);
    }

    public Menu create(Menu menu) {
        if (menu.getName() == null || menu.getName().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "菜单名称不能为空");
        }
        if (menu.getParentId() != null && menu.getParentId() != 0
                && menuMapper.selectById(menu.getParentId()) == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "父级菜单不存在");
        }
        if (menu.getMenuType() == null) {
            menu.setMenuType("PAGE");
        }
        if (menu.getStatus() == null) {
            menu.setStatus("ENABLED");
        }
        if (menu.getSort() == null) {
            menu.setSort(0);
        }
        menuMapper.insert(menu);
        return menuMapper.selectById(menu.getId());
    }

    /** 删除：有子菜单时拒绝，避免出现孤儿节点。 */
    public void delete(Long id) {
        Menu m = menuMapper.selectById(id);
        if (m == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "菜单不存在");
        }
        if (menuMapper.countChildren(id) > 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "该菜单下仍有子菜单，请先删除子级");
        }
        menuMapper.deleteById(id);
    }

    public void setStatus(Long id, String status) {
        if (!"ENABLED".equals(status) && !"PLANNED".equals(status)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "状态只能是 ENABLED 或 PLANNED");
        }
        if (menuMapper.updateStatus(id, status) == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "菜单不存在");
        }
    }

    // ---------------------------------------------------------------- 树构建

    private List<MenuNode> build(List<Menu> menus) {
        Map<Long, MenuNode> index = new LinkedHashMap<>();
        for (Menu m : menus) {
            index.put(m.getId(), toNode(m));
        }
        List<MenuNode> roots = new ArrayList<>();
        for (Menu m : menus) {
            MenuNode node = index.get(m.getId());
            MenuNode parent = m.getParentId() == null ? null : index.get(m.getParentId());
            if (parent == null) {
                // 父级不在结果集里（被权限过滤或本身是一级），按一级处理，避免节点丢失
                roots.add(node);
            } else {
                parent.getChildren().add(node);
            }
        }
        return roots;
    }

    private MenuNode toNode(Menu m) {
        MenuNode n = new MenuNode();
        n.setId(m.getId());
        n.setParentId(m.getParentId());
        n.setName(m.getName());
        n.setPath(m.getPath());
        n.setIcon(m.getIcon());
        n.setMenuType(m.getMenuType());
        n.setPermissionCode(m.getPermissionCode());
        n.setSort(m.getSort());
        n.setStatus(m.getStatus());
        return n;
    }
}
