package com.coolskyai.coolpilot.insight.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("analysis_task_batch")
public class AnalysisTaskBatch {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private Integer batchNo;
    private String status;
    private Integer inputCount;
    private Integer successCount;
    private Integer failedCount;
    private Long inputTokens;
    private Long outputTokens;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
