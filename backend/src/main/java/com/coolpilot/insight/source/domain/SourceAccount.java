package com.coolpilot.insight.source.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 平台账号。
 *
 * <p>双状态：{@code status} 是管理员启停意图（ACTIVE/DISABLED），
 * {@code authStatus} 是授权有效性（UNKNOWN/VALID/EXPIRED/INVALID/PENDING_VERIFY）。
 * 两者不可合并——"管理员停用"和"授权过期"的处理动作完全不同。
 */
@Data
public class SourceAccount {
    private Long id;
    private Long sourceId;
    private String accountName;
    private String externalAccountId;
    private String authType;
    /** 只存引用，绝不存明文凭证 */
    private String credentialRef;
    /** ACTIVE / DISABLED */
    private String status;
    private String authStatus;
    private LocalDateTime authorizedAt;
    private LocalDateTime expiresAt;
    private LocalDateTime lastVerifiedAt;
    private String lastErrorCode;
    private String lastErrorMessage;
    private String remark;
    private LocalDateTime lastUsedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
