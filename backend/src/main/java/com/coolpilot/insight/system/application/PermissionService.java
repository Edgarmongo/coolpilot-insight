package com.coolpilot.insight.system.application;

import com.coolpilot.insight.common.security.RequestContext;
import com.coolpilot.insight.common.exception.BizException;
import com.coolpilot.insight.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 身份与角色判定。
 *
 * <p>从 {@code GovernanceService} 拆出的「权限基础」部分：只回答"我是谁、是不是管理员"，
 * 不涉及任何业务资源的可见性——那属于各业务模块（如内容可见性在 content）。
 */
@Service
public class PermissionService {

    private static final Logger log = LoggerFactory.getLogger(PermissionService.class);
    private static final int ROLE_ADMIN = 1;

    private final PermissionQueryService permissionQueryService;

    public PermissionService(PermissionQueryService permissionQueryService) {
        this.permissionQueryService = permissionQueryService;
    }

    /**
     * 是否管理员。
     *
     * <p>以 RBAC 为准（user_role → ADMIN）。**兜底**：当某用户在 RBAC 中一个角色
     * 都没有时（历史数据、或绕过注册流程创建的用户），回落到旧 {@code user.role}
     * 并打 WARN，避免这类账号被一刀切成无权限。
     *
     * <p>兜底只在"RBAC 完全没数据"时生效；一旦该用户被分配了任一角色，就以 RBAC 为准。
     * 观察日志中不再出现该 WARN 后，可删除兜底。
     */
    public boolean isAdmin() {
        Long userId = RequestContext.getUserId();
        if (userId == null) {
            return false;
        }
        java.util.Set<String> roles = permissionQueryService.roleCodesOf(userId);
        if (roles.isEmpty()) {
            Integer legacyRole = RequestContext.getRole();
            boolean byLegacy = legacyRole != null && legacyRole == ROLE_ADMIN;
            if (byLegacy) {
                log.warn("[rbac] userId={} 无角色映射，回落 legacy role=1", userId);
            }
            return byLegacy;
        }
        return roles.contains("ADMIN");
    }

    public Long currentUserId() {
        return RequestContext.getUserId();
    }

    /** 未登录时抛出，由 GlobalExceptionHandler 转为 401。 */
    public Long requireLogin() {
        Long userId = currentUserId();
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "未登录");
        }
        return userId;
    }

    /** 非管理员时抛出，由 GlobalExceptionHandler 转为 403。 */
    public void requireAdmin() {
        if (!isAdmin()) {
            throw new BizException(ErrorCode.FORBIDDEN, "需要管理员权限");
        }
    }
}
