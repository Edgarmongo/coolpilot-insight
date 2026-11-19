package com.coolpilot.insight.collection.domain;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CollectionTask {
    private Long id;
    private String taskNo;
    private String taskType;
    private Long sourceId;
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
