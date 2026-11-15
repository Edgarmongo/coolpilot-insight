package com.coolpilot.insight.source.domain;

import lombok.Data;

import java.time.LocalDateTime;

/** 平台账号。凭据只存引用，不存明文。 */
@Data
public class SourceAccount {
    private Long id;
    private Long sourceId;
    private String accountName;
    private String externalAccountId;
    /** COOKIE / TOKEN / NONE */
    private String authType;
    private String credentialRef;
    /** ACTIVE / EXPIRED / DISABLED */
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime lastUsedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long tenantId;
}
