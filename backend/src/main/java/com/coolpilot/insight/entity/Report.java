package com.coolpilot.insight.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Report {
    private Long id;
    private String reportNo;
    private String title;
    /** VIDEO / KEYWORD / COMPARISON */
    private String reportType;
    private String scopeJson;
    private Long ownerUserId;
    /** DRAFT / PUBLISHED / ARCHIVED */
    private String status;
    private Integer currentVersion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
