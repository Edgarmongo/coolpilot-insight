package com.coolskyai.coolpilot.insight.service;

import com.coolskyai.coolpilot.insight.entity.AuditLog;
import com.coolskyai.coolpilot.insight.entity.SystemConfig;
import com.coolskyai.coolpilot.insight.interceptor.RequestContext;
import com.coolskyai.coolpilot.insight.mapper.AuditLogMapper;
import com.coolskyai.coolpilot.insight.mapper.SystemConfigMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计、系统配置与统一授权判定。
 *
 * <p>授权三层（D11）：
 * <ul>
 *   <li>L1 视频基础信息（标题/作者/统计）：不鉴权，平台公开信息</li>
 *   <li>L2 评论数据：管理员 / 该视频采集任务发起人 / 显式 video_grant 授权</li>
 *   <li>L3 分析结果：跟随 L2</li>
 * </ul>
 *
 * <p>本类是全项目唯一的授权判定入口——Python 侧通过 Java 传入的已鉴权 video_id 间接遵守同一规则。
 */
@Service
public class GovernanceService {

    private static final Logger log = LoggerFactory.getLogger(GovernanceService.class);

    private final AuditLogMapper auditMapper;
    private final SystemConfigMapper configMapper;
    private final com.coolskyai.coolpilot.insight.mapper.VideoGrantMapper grantMapper;
    private final com.coolskyai.coolpilot.insight.mapper.CollectionTaskItemMapper itemMapper;
    private final com.coolskyai.coolpilot.insight.mapper.CollectionTaskMapper taskMapper;

    private final Map<String, String> configCache = new HashMap<>();
    private volatile long configCacheAt = 0L;

    public GovernanceService(AuditLogMapper auditMapper,
                             SystemConfigMapper configMapper,
                             com.coolskyai.coolpilot.insight.mapper.VideoGrantMapper grantMapper,
                             com.coolskyai.coolpilot.insight.mapper.CollectionTaskItemMapper itemMapper,
                             com.coolskyai.coolpilot.insight.mapper.CollectionTaskMapper taskMapper) {
        this.auditMapper = auditMapper;
        this.configMapper = configMapper;
        this.grantMapper = grantMapper;
        this.itemMapper = itemMapper;
        this.taskMapper = taskMapper;
    }

    // ------------------------------------------------------------------ 授权

    public boolean isAdmin() {
        Integer role = RequestContext.getRole();
        return role != null && role == 1;
    }

    public Long currentUserId() {
        return RequestContext.getUserId();
    }

    /**
     * L2：是否可读取该视频的评论/分析数据。
     * 管理员、任务发起人、显式授权者三种之一即可。
     */
    public boolean canReadComments(Long videoId) {
        if (videoId == null) {
            return false;
        }
        if (isAdmin()) {
            return true;
        }
        Long userId = currentUserId();
        if (userId == null) {
            return false;
        }
        // 1) 显式授权且未过期
        Long grantCount = grantMapper.selectCount(new LambdaQueryWrapper<com.coolskyai.coolpilot.insight.entity.VideoGrant>()
                .eq(com.coolskyai.coolpilot.insight.entity.VideoGrant::getGranteeUserId, userId)
                .eq(com.coolskyai.coolpilot.insight.entity.VideoGrant::getVideoId, videoId)
                .and(w -> w.isNull(com.coolskyai.coolpilot.insight.entity.VideoGrant::getExpiresAt)
                        .or().gt(com.coolskyai.coolpilot.insight.entity.VideoGrant::getExpiresAt, LocalDateTime.now())));
        if (grantCount != null && grantCount > 0) {
            return true;
        }
        // 2) 本人发起过包含该视频的采集任务
        List<Long> taskIds = taskMapper.selectList(new LambdaQueryWrapper<com.coolskyai.coolpilot.insight.entity.CollectionTask>()
                        .eq(com.coolskyai.coolpilot.insight.entity.CollectionTask::getOwnerUserId, userId)
                        .orderByDesc(com.coolskyai.coolpilot.insight.entity.CollectionTask::getId)
                        .last("LIMIT 500"))
                .stream().map(com.coolskyai.coolpilot.insight.entity.CollectionTask::getId).collect(java.util.stream.Collectors.toList());
        if (taskIds.isEmpty()) {
            return false;
        }
        Long itemCount = itemMapper.selectCount(new LambdaQueryWrapper<com.coolskyai.coolpilot.insight.entity.CollectionTaskItem>()
                .eq(com.coolskyai.coolpilot.insight.entity.CollectionTaskItem::getVideoId, videoId)
                .in(com.coolskyai.coolpilot.insight.entity.CollectionTaskItem::getTaskId, taskIds));
        return itemCount != null && itemCount > 0;
    }

    /** 越权时抛出统一异常（由 GlobalExceptionHandler 转 403） */
    public void requireReadComments(Long videoId) {
        if (!canReadComments(videoId)) {
            throw new SecurityException("无权访问该视频的评论数据");
        }
    }

    /** L1 可见视频集合：管理员/全部，普通用户=自己任务涉及 + 被授权 */
    public List<Long> readableVideoIds() {
        if (isAdmin()) {
            return null; // null 代表无限制
        }
        Long userId = currentUserId();
        if (userId == null) {
            return java.util.Collections.emptyList();
        }
        java.util.Set<Long> ids = new java.util.HashSet<>();
        List<Long> taskIds = taskMapper.selectList(new LambdaQueryWrapper<com.coolskyai.coolpilot.insight.entity.CollectionTask>()
                        .eq(com.coolskyai.coolpilot.insight.entity.CollectionTask::getOwnerUserId, userId))
                .stream().map(com.coolskyai.coolpilot.insight.entity.CollectionTask::getId).collect(java.util.stream.Collectors.toList());
        if (!taskIds.isEmpty()) {
            itemMapper.selectList(new LambdaQueryWrapper<com.coolskyai.coolpilot.insight.entity.CollectionTaskItem>()
                            .in(com.coolskyai.coolpilot.insight.entity.CollectionTaskItem::getTaskId, taskIds))
                    .forEach(i -> ids.add(i.getVideoId()));
        }
        grantMapper.selectList(new LambdaQueryWrapper<com.coolskyai.coolpilot.insight.entity.VideoGrant>()
                        .eq(com.coolskyai.coolpilot.insight.entity.VideoGrant::getGranteeUserId, userId))
                .forEach(g -> ids.add(g.getVideoId()));
        return new java.util.ArrayList<>(ids);
    }

    // ------------------------------------------------------------------ 审计

    public void audit(String action, String resourceType, Long resourceId, String detailJson) {
        try {
            AuditLog log = new AuditLog();
            log.setUserId(currentUserId());
            log.setAction(action);
            log.setResourceType(resourceType);
            log.setResourceId(resourceId);
            log.setDetailJson(detailJson);
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest req = attrs.getRequest();
                log.setClientIp(clientIp(req));
                String ua = req.getHeader("User-Agent");
                log.setUserAgent(ua == null ? null : ua.substring(0, Math.min(ua.length(), 255)));
            }
            auditMapper.insert(log);
        } catch (Exception e) {
            // 审计失败不能影响主流程
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

    // ------------------------------------------------------------------ 配置

    public int getInt(String key, int def) {
        String v = get(key);
        try {
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public BigDecimal getDecimal(String key, BigDecimal def) {
        String v = get(key);
        try {
            return v == null ? def : new BigDecimal(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public String get(String key) {
        long now = System.currentTimeMillis();
        if (now - configCacheAt > 60_000L) {
            synchronized (configCache) {
                if (now - configCacheAt > 60_000L) {
                    configCache.clear();
                    configMapper.selectList(null)
                            .forEach(c -> configCache.put(c.getConfigKey(), c.getConfigValue()));
                    configCacheAt = now;
                }
            }
        }
        return configCache.get(key);
    }
}
