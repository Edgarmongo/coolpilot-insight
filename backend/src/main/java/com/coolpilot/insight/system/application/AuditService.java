package com.coolpilot.insight.system.application;

import com.coolpilot.insight.system.domain.AuditLog;
import com.coolpilot.insight.system.infrastructure.persistence.AuditLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 操作审计。
 *
 * <p>从 {@code GovernanceService} 拆出。审计失败不抛出异常——它不能影响主流程。
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogMapper auditMapper;
    private final PermissionService permissionService;

    public AuditService(AuditLogMapper auditMapper, PermissionService permissionService) {
        this.auditMapper = auditMapper;
        this.permissionService = permissionService;
    }

    public void audit(String action, String resourceType, Long resourceId, String detailJson) {
        try {
            AuditLog entry = new AuditLog();
            entry.setUserId(permissionService.currentUserId());
            entry.setAction(action);
            entry.setResourceType(resourceType);
            entry.setResourceId(resourceId);
            entry.setDetailJson(detailJson);

            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest req = attrs.getRequest();
                entry.setClientIp(clientIp(req));
                String ua = req.getHeader("User-Agent");
                entry.setUserAgent(ua == null ? null : ua.substring(0, Math.min(ua.length(), 255)));
            }
            auditMapper.insert(entry);
        } catch (Exception e) {
            // 审计是旁路，失败只记日志，不影响业务
            log.warn("[audit] 写入失败 action={}: {}", action, e.getMessage());
        }
    }

    private String clientIp(HttpServletRequest req) {
        String ip = req.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isEmpty()) {
            int comma = ip.indexOf(',');
            return comma > 0 ? ip.substring(0, comma).trim() : ip.trim();
        }
        return req.getRemoteAddr();
    }
}
