package com.coolskyai.coolpilot.insight.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("collection_task_log")
public class CollectionTaskLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private Long taskItemId;
    private String eventType;
    private String eventLevel;
    private String message;
    private String detailsJson;
    private LocalDateTime createdAt;
}
