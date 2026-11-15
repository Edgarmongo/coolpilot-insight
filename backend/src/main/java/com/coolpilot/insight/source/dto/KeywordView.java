package com.coolpilot.insight.source.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 关键词视图。 */
@Data
public class KeywordView {
    private Long id;
    private Long sourceId;
    private String sourceCode;
    private String keyword;
    private String status;
    private String discoverPolicy;
    private LocalDateTime lastDiscoveredAt;
    private LocalDateTime createdAt;
}
