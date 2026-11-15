package com.coolskyai.coolpilot.insight.utils;

import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TokenStore {
    // token → userId
    private final Map<String, Long> tokens = new ConcurrentHashMap<>();
    private final Map<String, Long> expires = new ConcurrentHashMap<>();

    public void put(String token, Long userId, long ttlMs) {
        tokens.put(token, userId);
        expires.put(token, System.currentTimeMillis() + ttlMs);
        cleanExpired();
    }

    public boolean exists(String token) {
        cleanExpired();
        Long exp = expires.get(token);
        if (exp == null) return false;
        if (System.currentTimeMillis() > exp) {
            tokens.remove(token);
            expires.remove(token);
            return false;
        }
        return true;
    }

    public void remove(String token) {
        tokens.remove(token);
        expires.remove(token);
    }

    public void removeByUserId(Long userId) {
        Iterator<Map.Entry<String, Long>> it = tokens.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (e.getValue().equals(userId)) {
                expires.remove(e.getKey());
                it.remove();
            }
        }
    }

    private void cleanExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> it = expires.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (now > e.getValue()) {
                tokens.remove(e.getKey());
                it.remove();
            }
        }
    }
}
