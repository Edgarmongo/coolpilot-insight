package com.coolpilot.insight.system.application;

import com.coolpilot.insight.common.web.PageResult;
import com.coolpilot.insight.system.dto.AuditLogView;
import com.coolpilot.insight.system.infrastructure.persistence.AuditLogMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/** 审计日志查询（只读，管理员）。 */
@Service
public class AuditQueryService {

    private final AuditLogMapper auditMapper;

    public AuditQueryService(AuditLogMapper auditMapper) {
        this.auditMapper = auditMapper;
    }

    /**
     * @param action 可选，精确匹配动作名
     * @param userId 可选，按操作人过滤
     */
    public PageResult<AuditLogView> page(String action, Long userId, int page, int size) {
        int current = Math.max(page, 1);
        int pageSize = Math.min(Math.max(size, 1), 100);
        int offset = (current - 1) * pageSize;

        long total = auditMapper.countFiltered(action, userId);
        List<AuditLogView> records = auditMapper.selectPage(action, userId, pageSize, offset)
                .stream().map(AuditQueryService::toView).toList();

        return PageResult.of(records, total, current, pageSize);
    }

    private static AuditLogView toView(com.coolpilot.insight.system.domain.AuditLog a) {
        AuditLogView v = new AuditLogView();
        v.setId(a.getId());
        v.setUserId(a.getUserId());
        v.setAction(a.getAction());
        v.setResourceType(a.getResourceType());
        v.setResourceId(a.getResourceId());
        v.setDetailJson(a.getDetailJson());
        v.setClientIp(a.getClientIp());
        v.setCreatedAt(a.getCreatedAt());
        return v;
    }
}
