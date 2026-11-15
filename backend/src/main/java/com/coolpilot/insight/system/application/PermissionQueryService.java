package com.coolpilot.insight.system.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RBAC 权限读取。
 *
 * <p>用户 → 角色 → 权限码，进程内缓存 60 秒；角色/权限变更后调用 {@link #invalidate()}
 * 主动失效，避免"改了权限要等一分钟才生效"。
 *
 * <p>缓存是进程内的：多实例部署时各实例独立，这与现有 {@code ConfigService} 一致，
 * 首期单实例可接受，已记入已知限制。
 */
@Service
public class PermissionQueryService {

    private static final long TTL_MS = 60_000L;

    private final JdbcTemplate jdbc;

    private final Map<Long, Cached> cache = new HashMap<>();

    public PermissionQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Set<String> roleCodesOf(Long userId) {
        Cached c = load(userId);
        return c.roles;
    }

    public Set<String> permissionCodesOf(Long userId) {
        Cached c = load(userId);
        return c.permissions;
    }

    public boolean hasRole(Long userId, String roleCode) {
        return userId != null && roleCodesOf(userId).contains(roleCode);
    }

    public boolean hasPermission(Long userId, String code) {
        return userId != null && permissionCodesOf(userId).contains(code);
    }

    /** 角色/权限变更后调用。 */
    public synchronized void invalidate() {
        cache.clear();
    }

    public synchronized void invalidate(Long userId) {
        cache.remove(userId);
    }

    private Cached load(Long userId) {
        if (userId == null) {
            return Cached.EMPTY;
        }
        long now = System.currentTimeMillis();
        Cached c = cache.get(userId);
        if (c != null && now - c.loadedAt < TTL_MS) {
            return c;
        }
        List<String> roles = jdbc.queryForList(
                "SELECT r.code FROM sys_role r JOIN sys_user_role ur ON ur.role_id = r.id "
                        + "WHERE ur.user_id = ? AND r.status = 'ENABLED'", String.class, userId);
        List<String> perms = jdbc.queryForList(
                "SELECT DISTINCT p.code FROM sys_permission p "
                        + "JOIN sys_role_permission rp ON rp.permission_id = p.id "
                        + "JOIN sys_user_role ur ON ur.role_id = rp.role_id "
                        + "JOIN sys_role r ON r.id = rp.role_id "
                        + "WHERE ur.user_id = ? AND r.status = 'ENABLED'", String.class, userId);
        Cached fresh = new Cached(new HashSet<>(roles), new HashSet<>(perms), now);
        cache.put(userId, fresh);
        return fresh;
    }

    private record Cached(Set<String> roles, Set<String> permissions, long loadedAt) {
        static final Cached EMPTY = new Cached(Collections.emptySet(), Collections.emptySet(), 0L);
    }
}
