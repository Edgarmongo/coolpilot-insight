package com.coolpilot.insight.source.domain;

import lombok.Data;

import java.time.LocalDateTime;

/** 关键词与发现策略（属于 source：定义"去哪儿找"）。 */
@Data
public class SourceKeyword {
    private Long id;
    private Long sourceId;
    private String keyword;
    /** 小写去空格，用于同源同用户去重 */
    private String normalized;
    private Long ownerUserId;
    /** ENABLED / DISABLED */
    private String status;
    /** MANUAL / DAILY */
    private String discoverPolicy;
    private LocalDateTime lastDiscoveredAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long tenantId;
}
