package com.coolpilot.insight.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CollectionTask {
    private Long id;
    private String taskNo;
    private String taskType;
    private String status;
    private Long ownerUserId;
    private String paramsJson;
    private Integer totalItems;
    private Integer successItems;
    private Integer failedItems;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime updatedAt;
}
