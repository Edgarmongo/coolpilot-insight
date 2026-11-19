package com.coolpilot.insight.system.domain.role;

import lombok.Data;

import java.time.LocalDateTime;

/** 角色。builtin=1 的角色不可删除（ADMIN/USER/REVIEWER）。 */
@Data
public class Role {
    private Long id;
    private String code;
    private String name;
    private Integer builtin;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
