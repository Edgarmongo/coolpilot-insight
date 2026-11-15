package com.coolskyai.coolpilot.insight.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ReportVersion {
    private Long id;
    private Long reportId;
    private Integer versionNo;
    /** 唯一权威内容 */
    private String contentJson;
    private String contentMd;
    private Long analysisTaskId;
    private Integer evidenceCount;
    private Long generatedBy;
    private LocalDateTime generatedAt;
}
