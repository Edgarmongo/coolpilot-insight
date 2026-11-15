package com.coolpilot.insight.system.application;

import com.coolpilot.insight.common.security.RequestContext;
import org.springframework.stereotype.Service;

/**
 * 身份与角色判定。
 *
 * <p>从 {@code GovernanceService} 拆出的「权限基础」部分：只回答"我是谁、是不是管理员"，
 * 不涉及任何业务资源的可见性——那属于各业务模块（如内容可见性在 content）。
 */
@Service
public class PermissionService {

    private static final int ROLE_ADMIN = 1;

    public boolean isAdmin() {
        Integer role = RequestContext.getRole();
        return role != null && role == ROLE_ADMIN;
    }

    public Long currentUserId() {
        return RequestContext.getUserId();
    }

    /** 未登录时抛出，由 GlobalExceptionHandler 转为 401。 */
    public Long requireLogin() {
        Long userId = currentUserId();
        if (userId == null) {
            throw new SecurityException("未登录");
        }
        return userId;
    }

    /** 非管理员时抛出，由 GlobalExceptionHandler 转为 403。 */
    public void requireAdmin() {
        if (!isAdmin()) {
            throw new SecurityException("需要管理员权限");
        }
    }
}
