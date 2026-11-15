package com.coolskyai.coolpilot.insight.system.dto;

import java.time.LocalDateTime;

/**
 * 用户管理视图。
 *
 * <p>与 {@code User} 领域模型的区别：不含 passwordHash。
 * 脱敏用独立视图而不是「把实体字段置空再返回」，避免领域对象被展示逻辑污染。
 */
public class UserView {

    private Long id;
    private String username;
    /** 已脱敏：138****8000 */
    private String phone;
    private Integer role;
    private Integer status;
    private Integer mustChangePwd;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public Integer getRole() {
        return role;
    }

    public void setRole(Integer role) {
        this.role = role;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getMustChangePwd() {
        return mustChangePwd;
    }

    public void setMustChangePwd(Integer mustChangePwd) {
        this.mustChangePwd = mustChangePwd;
    }

    public LocalDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(LocalDateTime lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
