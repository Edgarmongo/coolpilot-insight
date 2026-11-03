package com.coolskyai.coolpilot.insight.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("collection_task_item")
public class CollectionTaskItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private Long videoId;
    private String status;
    /** 活动任务标记；终态置 NULL（唯一索引对 NULL 不生效） */
    private Integer activeFlag;
    private String workerId;
    private Integer retryCount;
    private Integer maxRetry;
    private Integer requestedMax;
    private Long collectedCount;
    private Long skippedCount;
    private LocalDateTime heartbeatAt;
    private LocalDateTime leaseUntil;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime createdAt;
}
