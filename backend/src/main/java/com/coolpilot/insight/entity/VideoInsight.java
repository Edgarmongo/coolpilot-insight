package com.coolpilot.insight.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class VideoInsight {
    private Long id;
    private Long videoId;
    private Long analysisTaskId;
    private Long profileId;
    /** OVERVIEW / PAIN_POINTS / PRODUCT_OPPORTUNITY */
    private String insightType;
    private String scopeJson;
    private Integer sampleCount;
    private String resultJson;
    private String summaryMd;
    private Integer evidenceCount;
    private LocalDateTime createdAt;
}
