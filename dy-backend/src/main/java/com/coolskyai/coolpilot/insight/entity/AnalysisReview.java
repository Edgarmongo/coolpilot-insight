package com.coolskyai.coolpilot.insight.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AnalysisReview {
    private Long id;
    private Long commentAnalysisId;
    private Long reviewerUserId;
    /** APPROVED / REJECTED / CORRECTED */
    private String status;
    private String note;
    /** 人工纠正后的结构化字段快照 */
    private String correctedJson;
    private LocalDateTime createdAt;
}
