package com.coolskyai.coolpilot.insight.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AuditLog {
    private Long id;
    private Long userId;
    private String action;
    private String resourceType;
    private Long resourceId;
    private String detailJson;
    private String clientIp;
    private String userAgent;
    private LocalDateTime createdAt;
}
