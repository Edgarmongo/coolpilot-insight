package com.coolskyai.coolpilot.insight.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SystemConfig {
    private Long id;
    private String configKey;
    private String configValue;
    /** STRING / INT / DECIMAL / BOOL / JSON */
    private String valueType;
    /** 仅控制 API 脱敏，非加密 */
    private Integer isSecret;
    private String description;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
