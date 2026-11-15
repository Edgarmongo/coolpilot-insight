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
     * <p><b>过渡期双读</b>：以 RBAC（user_role → ADMIN）为准，
     * 同时比对旧 {@code user.role}，不一致时打 WARN。
     * 不一致说明迁移有遗漏——这是唯一能在不阻塞业务的前提下发现权限错位的手段。
     * 待连续观察无差异后，删除 legacy 判断与告警。
     */
    public boolean isAdmin() {
        Long userId = RequestContext.getUserId();
        if (userId == null) {
            return false;
        }
        boolean byRbac = permissionQueryService.hasRole(userId, "ADMIN");
        Integer legacyRole = RequestContext.getRole();
        boolean byLegacy = legacyRole != null && legacyRole == ROLE_ADMIN;
        if (byRbac != byLegacy) {
            log.warn("[rbac] 判定不一致 userId={} rbac={} legacy={}", userId, byRbac, byLegacy);
        }
        return byRbac;
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
