package com.coolpilot.insight.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AnalysisTaskBatch {
    private Long id;
    private Long taskId;
    private Integer batchNo;
    private String status;
    private Integer inputCount;
    private Integer successCount;
    private Integer failedCount;
    private Long inputTokens;
    private Long outputTokens;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
