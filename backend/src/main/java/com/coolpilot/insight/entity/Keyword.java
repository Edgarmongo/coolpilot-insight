package com.coolpilot.insight.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Keyword {
    private Long id;
    private String keyword;
    /** 归一化：小写 + 去空格 + 全角转半角 */
    private String normalized;
    private String description;
    private Long ownerUserId;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
