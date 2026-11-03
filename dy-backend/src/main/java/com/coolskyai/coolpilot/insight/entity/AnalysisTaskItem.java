package com.coolskyai.coolpilot.insight.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("analysis_task_item")
public class AnalysisTaskItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private Long batchId;
    /** COMMENT / VIDEO */
    private String targetType;
    private Long targetId;
    private String status;
    private Integer retryCount;
    private Integer allocatedInputTokens;
    private Integer allocatedOutputTokens;
    private BigDecimal allocatedCostAmount;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
}
