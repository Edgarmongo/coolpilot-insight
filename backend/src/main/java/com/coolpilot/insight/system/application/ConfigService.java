package com.coolpilot.insight.system.application;

import com.coolpilot.insight.system.infrastructure.persistence.SystemConfigMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 系统配置读取（带 60 秒本地缓存）。
 *
 * <p>从 {@code GovernanceService} 拆出。缓存是进程内实现，多实例部署时各自持有副本，
 * 因此配置变更后最长 60 秒生效——这是有意的取舍，避免为配置加 Redis。
 */
@Service
public class ConfigService {

    private static final long TTL_MS = 60_000L;

    private final SystemConfigMapper configMapper;
    private final Map<String, String> cache = new HashMap<>();
    private volatile long cachedAt = 0L;

    public ConfigService(SystemConfigMapper configMapper) {
        this.configMapper = configMapper;
    }

    public String get(String key) {
        long now = System.currentTimeMillis();
        if (now - cachedAt > TTL_MS) {
            synchronized (cache) {
                if (now - cachedAt > TTL_MS) {
                    cache.clear();
                    configMapper.selectAllConfig()
                            .forEach(c -> cache.put(c.getConfigKey(), c.getConfigValue()));
                    cachedAt = now;
                }
            }
        }
        return cache.get(key);
    }

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

    /** 配置被修改后调用，强制下次读取回源。 */
    public void invalidate() {
        synchronized (cache) {
            cache.clear();
            cachedAt = 0L;
        }
    }
}
