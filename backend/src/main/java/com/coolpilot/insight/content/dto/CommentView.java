package com.coolpilot.insight.content.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 评论视图。字段名沿用前端既有约定（cid / content / videoId）。 */
@Data
public class CommentView {
    private Long id;
    private Long videoId;
    private String cid;
    private String parentCid;
    private String nickname;
    private String content;
    private Integer likes;
    private String ipRegion;
    private LocalDateTime publishTime;
    private Integer isReply;
}
