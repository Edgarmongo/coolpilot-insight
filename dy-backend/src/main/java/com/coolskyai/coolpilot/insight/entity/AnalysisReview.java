package com.coolskyai.coolpilot.insight.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("analysis_review")
public class AnalysisReview {
    @TableId(type = IdType.AUTO)
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
