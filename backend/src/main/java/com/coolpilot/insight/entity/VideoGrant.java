package com.coolpilot.insight.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class VideoGrant {
    private Long id;
    private Long granteeUserId;
    private Long videoId;
    /** 第一版仅 READ */
    private String permission;
    private Long grantedBy;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
}
