package com.coolskyai.coolpilot.insight.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("analysis_task")
public class AnalysisTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskNo;
    private String taskType;
    private String status;
    private Long ownerUserId;
    private String scopeType;
    private String scopeJson;
    private Long profileId;
    private Integer totalItems;
    private Integer successItems;
    private Integer failedItems;
    private Long inputTokens;
    private Long outputTokens;
    private BigDecimal costAmount;
    private String priceVersion;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String errorMessage;
}
