package com.coolskyai.coolpilot.insight.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("comment_analysis")
public class CommentAnalysis {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long commentId;
    private Long profileId;
    private Long analysisTaskId;
    private Long analysisTaskItemId;
    /** positive/neutral/negative/unknown */
    private String sentiment;
    /** complaint/praise/question/purchase/other */
    private String intent;
    private String topicsJson;
    private String painPointsJson;
    private String userNeed;
    /** none/low/medium/high */
    private String purchaseIntent;
    private String scenarioTagsJson;
    private String productSuggestionsJson;
    private String competitorMentionsJson;
    private BigDecimal confidence;
    private Integer isUncertain;
    /** PENDING / APPROVED / REJECTED / CORRECTED */
    private String reviewStatus;
    private Long reviewedBy;
    private LocalDateTime reviewedAt;
    private LocalDateTime createdAt;
}
