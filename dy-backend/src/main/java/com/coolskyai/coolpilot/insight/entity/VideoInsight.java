package com.coolskyai.coolpilot.insight.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("video_insight")
public class VideoInsight {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long videoId;
    private Long analysisTaskId;
    private Long profileId;
    /** OVERVIEW / PAIN_POINTS / PRODUCT_OPPORTUNITY */
    private String insightType;
    private String scopeJson;
    private Integer sampleCount;
    private String resultJson;
    private String summaryMd;
    private Integer evidenceCount;
    private LocalDateTime createdAt;
}
