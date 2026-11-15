package com.coolskyai.coolpilot.insight.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class AnalysisTaskItem {
    private Long id;
    private Long taskId;
    private Long batchId;
    /** COMMENT / VIDEO */
    private String targetType;
    private Long targetId;
    private String status;
    private Integer retryCount;
    private Integer allocatedInputTokens;
    private Integer allocatedOutputTokens;
    private BigDecimal allocatedCostAmount;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
}
