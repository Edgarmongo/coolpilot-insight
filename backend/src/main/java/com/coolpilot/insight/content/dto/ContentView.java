package com.coolpilot.insight.content.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 内容视图（对外暴露的字段，不含原始响应与内部状态位）。 */
@Data
public class ContentView {
    private Long id;
    private Long sourceId;
    private String externalContentId;
    private String contentType;
    private String title;
    private String sourceUrl;
    private LocalDateTime publishedAt;
    private String normalizeStatus;
}
