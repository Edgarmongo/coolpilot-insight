package com.coolpilot.insight.system.application.role;

import com.coolpilot.insight.system.domain.role.Role;
import com.coolpilot.insight.system.dto.role.RoleView;
import com.coolpilot.insight.system.infrastructure.role.RoleMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/** 角色查询（只读）。与命令分离，便于将来加缓存而不影响写路径。 */
@Service
public class RoleQueryService {

    private final RoleMapper roleMapper;

    public RoleQueryService(RoleMapper roleMapper) {
        this.roleMapper = roleMapper;
    }

    public List<RoleView> list() {
        return roleMapper.selectAll().stream().map(this::toView).toList();
    }

    public List<String> rolesOf(Long userId) {
        return roleMapper.selectByUserId(userId).stream().map(Role::getCode).toList();
    }

    RoleView toView(Role r) {
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
