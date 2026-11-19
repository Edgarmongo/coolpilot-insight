package com.coolpilot.insight.content.domain;

import lombok.Data;

import java.time.LocalDateTime;

/** 作者公开标识（对应 ct_author）。只存平台公开字段，不做隐私推断。 */
@Data
public class Author {
    private Long id;
    private Long sourceId;
    private String externalAuthorId;
    private String displayName;
    private String profileUrl;
    private String publicProfileJson;
    private LocalDateTime firstSeenAt;
    private LocalDateTime lastSeenAt;
}
