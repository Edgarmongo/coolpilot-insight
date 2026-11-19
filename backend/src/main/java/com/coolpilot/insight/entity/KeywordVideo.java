package com.coolpilot.insight.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class KeywordVideo {
    private Long id;
    private Long keywordId;
    private Long videoId;
    private LocalDateTime discoveredAt;
}
