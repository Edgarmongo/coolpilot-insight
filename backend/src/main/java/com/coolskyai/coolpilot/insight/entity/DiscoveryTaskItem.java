package com.coolskyai.coolpilot.insight.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DiscoveryTaskItem {
    private Long id;
    private Long taskId;
    private Long videoId;
    private Integer position;
    private Integer isNew;
    private String rawJson;
    private LocalDateTime createdAt;
}
