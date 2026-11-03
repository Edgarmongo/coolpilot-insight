package com.coolskyai.coolpilot.insight.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("report")
public class Report {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String reportNo;
    private String title;
    /** VIDEO / KEYWORD / COMPARISON */
    private String reportType;
    private String scopeJson;
    private Long ownerUserId;
    /** DRAFT / PUBLISHED / ARCHIVED */
    private String status;
    private Integer currentVersion;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
