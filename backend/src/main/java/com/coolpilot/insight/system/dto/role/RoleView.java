package com.coolpilot.insight.system.dto.role;

import lombok.Data;

import java.util.List;

/** 角色视图：带上权限 id 与关联用户数，便于管理页直接展示。 */
@Data
public class RoleView {
    private Long id;
    private String code;
    private String name;
    private boolean builtin;
    private String status;
    private int userCount;
    private List<Long> permissionIds;
}
