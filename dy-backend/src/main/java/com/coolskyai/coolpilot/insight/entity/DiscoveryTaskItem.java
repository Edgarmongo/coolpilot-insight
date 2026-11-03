package com.coolskyai.coolpilot.insight.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("discovery_task_item")
public class DiscoveryTaskItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private Long videoId;
    private Integer position;
    private Integer isNew;
    private String rawJson;
    private LocalDateTime createdAt;
}
