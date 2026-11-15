package com.coolpilot.insight.collection.domain;

import lombok.Data;

import java.time.LocalDateTime;

/** 采集执行单元（col_execution）。一次 invokeCollector = 一个 Execution。 */
@Data
public class ColExecution {
    private Long id;
    private Long runId;
    private Long sourceId;
    private Long accountId;
    private String authMode;
    private Long policyId;
    private String capability;
    private String status;
    private Integer attemptNo;
    private Integer requestCount;
    private Integer fetchedCount;
    private Integer insertedCount;
    private Integer duplicateCount;
    private Integer failedCount;
    private String failureType;
    private String errorCode;
    private String errorMessage;
    private String adapterVersion;
    private String idemKey;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
}
