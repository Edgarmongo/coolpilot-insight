package com.coolskyai.coolpilot.insight.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DiscoveryTask {
    private Long id;
    private String taskNo;
    private Long keywordId;
    private String status;
    private Long ownerUserId;
    private Integer requestedMax;
    private Integer foundCount;
    private Integer newVideoCount;
    private String paramsJson;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String errorMessage;
}
