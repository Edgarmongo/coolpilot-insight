package com.coolpilot.insight.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CollectionCheckpoint {
    private Long id;
    private Long taskItemId;
    private String cursorValue;
    private Integer pageNo;
    private String lastCid;
    private Long persistedCount;
    private LocalDateTime lastSuccessAt;
    private LocalDateTime updatedAt;
}
