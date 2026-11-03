package com.coolskyai.coolpilot.insight.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("collection_task")
public class CollectionTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskNo;
    private String taskType;
    private String status;
    private Long ownerUserId;
    private String paramsJson;
    private Integer totalItems;
    private Integer successItems;
    private Integer failedItems;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime updatedAt;
}
