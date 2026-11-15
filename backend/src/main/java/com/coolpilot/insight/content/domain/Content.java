package com.coolpilot.insight.content.domain;

import lombok.Data;

import java.time.LocalDateTime;

/** 标准化内容（对应 ct_content）。与原始归档 {@code RawArchive} 分离。 */
@Data
public class Content {
    private Long id;
    private Long sourceId;
    private String externalContentId;
    private String contentType;
    private String title;
    private String contentText;
    private String sourceUrl;
    private Long authorId;
    private Long rawArchiveId;
    private String contentHash;
    /** RAW / NORMALIZED / FAILED */
    private String normalizeStatus;
    private LocalDateTime publishedAt;
    private LocalDateTime collectedAt;
    private String metricsJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long tenantId;
}
