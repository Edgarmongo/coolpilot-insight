package com.coolskyai.coolpilot.insight.utils;

import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MemoryCache {
    private final Map<String, CacheEntry> map = new ConcurrentHashMap<>();

    public void put(String key, String value, long ttlMs) {
        map.put(key, new CacheEntry(value, System.currentTimeMillis() + ttlMs));
    }

    public String get(String key) {
        CacheEntry entry = map.get(key);
        if (entry == null) return null;
        if (System.currentTimeMillis() > entry.expireAt) {
            map.remove(key);
            return null;
        }
        return entry.value;
    }

    public void delete(String key) {
        map.remove(key);
    }

    public void deleteByPrefix(String prefix) {
        Iterator<Map.Entry<String, CacheEntry>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, CacheEntry> e = it.next();
            if (e.getKey().startsWith(prefix)) {
                it.remove();
            }
        }
    }

    private static class CacheEntry {
        final String value;
        final long expireAt;
        CacheEntry(String value, long expireAt) {
            this.value = value;
            this.expireAt = expireAt;
        }
    }
}
