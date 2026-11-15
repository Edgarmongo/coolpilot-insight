package com.coolpilot.insight.system.application.role;

import com.coolpilot.insight.common.exception.BizException;
import com.coolpilot.insight.common.exception.ErrorCode;
import com.coolpilot.insight.system.application.AuditService;
import com.coolpilot.insight.system.domain.role.Role;
import com.coolpilot.insight.system.dto.role.RoleView;
import com.coolpilot.insight.system.infrastructure.role.RoleMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 角色管理（RBAC 的骨架）。
 *
 * <p>两条硬规则：
 * 1. **内置角色不可删除**（builtin=1），否则可能把 ADMIN 删掉导致无人可管理系统；
 * 2. 角色仍有用户关联时不可删除，否则这些用户会静默失去全部权限。
 */
@Service
public class RoleService {

    private final RoleMapper roleMapper;
    private final AuditService auditService;

    public RoleService(RoleMapper roleMapper, AuditService auditService) {
        this.roleMapper = roleMapper;
        this.auditService = auditService;
    }

    public List<RoleView> list() {
        return roleMapper.selectAll().stream().map(this::toView).toList();
    }

    public RoleView create(String code, String name) {
        String c = code == null ? "" : code.trim().toUpperCase();
        String n = name == null ? "" : name.trim();
        if (c.isEmpty() || !c.matches("[A-Z][A-Z0-9_]{1,31}")) {
            throw new BizException(ErrorCode.PARAM_INVALID, "角色编码需为 2-32 位大写字母/数字/下划线");
        }
        if (n.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "角色名称不能为空");
        }
        if (roleMapper.selectByCode(c) != null) {
            throw new BizException(ErrorCode.DUPLICATE, "角色编码已存在: " + c);
        }

        Role r = new Role();
        r.setCode(c);
        r.setName(n);
        roleMapper.insert(r);
        auditService.audit("ROLE_CREATE", "SYS_ROLE", r.getId(), "{\"code\":\"" + c + "\"}");
        return toView(roleMapper.selectById(r.getId()));
    }

    public void delete(Long id) {
        Role r = roleMapper.selectById(id);
        if (r == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "角色不存在");
        }
        if (r.getBuiltin() != null && r.getBuiltin() == 1) {
            throw new BizException(ErrorCode.FORBIDDEN, "内置角色不可删除");
        }
        int users = roleMapper.countUsers(id);
        if (users > 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "该角色仍关联 " + users + " 个用户，请先解除关联");
        }
        roleMapper.deleteNonBuiltin(id);
        auditService.audit("ROLE_DELETE", "SYS_ROLE", id, "{\"code\":\"" + r.getCode() + "\"}");
    }

    public void assignRole(Long userId, Long roleId) {
        if (roleMapper.selectById(roleId) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "角色不存在");
        }
        roleMapper.assign(userId, roleId);
        auditService.audit("ROLE_ASSIGN", "SYS_USER", userId, "{\"roleId\":" + roleId + "}");
    }

    public void revokeRole(Long userId, Long roleId) {
        roleMapper.revoke(userId, roleId);
        auditService.audit("ROLE_REVOKE", "SYS_USER", userId, "{\"roleId\":" + roleId + "}");
    }

    public List<String> rolesOf(Long userId) {
        return roleMapper.selectByUserId(userId).stream().map(Role::getCode).toList();
    }

    private RoleView toView(Role r) {
        RoleView v = new RoleView();
        v.setId(r.getId());
        v.setCode(r.getCode());
        v.setName(r.getName());
        v.setBuiltin(r.getBuiltin() != null && r.getBuiltin() == 1);
        v.setStatus(r.getStatus());
        v.setUserCount(roleMapper.countUsers(r.getId()));
        v.setPermissionIds(roleMapper.selectPermissionIds(r.getId()));
        return v;
    }
}
