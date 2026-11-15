package com.coolpilot.insight.collection.domain;

import lombok.Data;

import java.time.LocalDateTime;

/** 采集运行实例（col_run）。PENDING 只存在于本层。 */
@Data
public class ColRun {
    private Long id;
    private Long taskId;
    private Integer runNo;
    /** MANUAL / SCHEDULED */
    private String triggerType;
    /** PENDING/RUNNING/COMPLETED/PARTIAL_SUCCESS/FAILED/PAUSED/CANCELLED */
    private String status;
    private String configSnapshot;
    private Integer totalExecutionCount;
    private Integer successCount;
    private Integer failedCount;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String errorSummary;
    private LocalDateTime createdAt;
}
