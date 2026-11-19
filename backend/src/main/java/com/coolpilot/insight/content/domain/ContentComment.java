package com.coolpilot.insight.content.domain;

import lombok.Data;

import java.time.LocalDateTime;

/** 评论（对应 ct_comment）。dedupKey 恒非空，用于 cid 缺失时的兜底去重。 */
@Data
public class ContentComment {
    private Long id;
    private Long contentId;
    private Long sourceId;
    private String externalCommentId;
    private String parentExternalId;
    private Integer isReply;
    private Long authorId;
    private String nickname;
    private String text;
    private Integer likes;
    private String ipRegion;
    private LocalDateTime publishedAt;
    private String dedupKey;
    /** CID / FINGERPRINT */
    private String dedupStrategy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long tenantId;
}
