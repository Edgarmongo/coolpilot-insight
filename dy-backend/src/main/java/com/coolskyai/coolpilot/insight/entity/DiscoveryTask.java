package com.coolskyai.coolpilot.insight.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("discovery_task")
public class DiscoveryTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskNo;
    private Long keywordId;
    private String status;
    private Long ownerUserId;
    private Integer requestedMax;
    private Integer foundCount;
    private Integer newVideoCount;
    private String paramsJson;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String errorMessage;
}
