package com.coolskyai.coolpilot.insight.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("video")
public class Video {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String platform;
    /** 平台视频 ID（抖音 aweme_id） */
    private String awemeId;
    private String title;
    private String authorName;
    private String authorId;
    private String coverUrl;
    private LocalDateTime publishTime;
    private Long diggCount;
    private Long commentCount;
    private Long shareCount;
    private Integer status;
    private LocalDateTime firstDiscoveredAt;
    private LocalDateTime lastCollectedAt;
    private String metadataJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
