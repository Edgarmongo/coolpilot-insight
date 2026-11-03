package com.coolskyai.coolpilot.insight.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("analysis_evidence")
public class AnalysisEvidence {
    @TableId(type = IdType.AUTO)
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
