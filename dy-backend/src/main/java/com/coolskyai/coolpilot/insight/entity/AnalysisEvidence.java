package com.coolskyai.coolpilot.insight.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class AnalysisEvidence {
    private Long id;
    private Long insightId;
    private Long commentAnalysisId;
    private Long commentId;
    /** SUPPORTING / CONTRADICTING / REPRESENTATIVE */
    private String evidenceType;
    private BigDecimal relevanceScore;
    private String explanation;
    /** INSIGHT / COMMENT_ANALYSIS（U4） */
    private String sourceLevel;
    private LocalDateTime createdAt;
}
