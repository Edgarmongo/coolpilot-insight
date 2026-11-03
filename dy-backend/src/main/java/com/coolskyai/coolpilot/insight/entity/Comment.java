package com.coolskyai.coolpilot.insight.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("comment")
public class Comment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String platform;
    /** 平台评论 ID，允许 NULL（存在性未验证） */
    private String cid;
    /** 幂等键，恒非空：优先 cid，否则本地指纹 */
    private String dedupKey;
    /** CID / FINGERPRINT */
    private String dedupStrategy;
    /** 内部 video.id（ADR-003），不是平台 aweme_id */
    private Long videoId;
    private String parentCid;
    private String rootCid;
    private Integer isReply;
    private Integer replyCount;
    private String nickname;
    /** 评论者抖音号（unique_id 或回退 short_id） */
    private String douyinId;
    private String douyinUid;
    private String secUid;
    /** IP 归属地（抖音仅返回归属地标签，不返回真实 IP） */
    private String ipRegion;
    private String content;
    private Integer likes;
    private LocalDateTime publishTime;
    private Long sourceItemId;
    private LocalDateTime collectedAt;
    private LocalDateTime updatedAt;
}
