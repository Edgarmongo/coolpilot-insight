package com.coolpilot.insight.collection.domain;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CollectionTaskLog {
    private Long id;
    private Long taskId;
    private Long taskItemId;
    private String eventType;
    private String eventLevel;
    private String message;
    private String detailsJson;
    private LocalDateTime createdAt;
}
