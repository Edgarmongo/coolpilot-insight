package com.coolskyai.coolpilot.insight.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("video_grant")
public class VideoGrant {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long granteeUserId;
    private Long videoId;
    /** 第一版仅 READ */
    private String permission;
    private Long grantedBy;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
}
