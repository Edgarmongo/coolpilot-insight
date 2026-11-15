package com.coolpilot.insight.system.domain;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class User {
    private Long id;
    private String username;
    private String phone;
    /** 仅 BCrypt（ADR-005）；哨兵值表示待初始化 */
    private String passwordHash;
    /** 0=普通 1=管理员 */
    private Integer role;
    /** 1=正常 0=禁用 */
    private Integer status;
    private Integer mustChangePwd;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
