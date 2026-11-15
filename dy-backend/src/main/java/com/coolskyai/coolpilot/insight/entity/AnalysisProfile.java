package com.coolskyai.coolpilot.insight.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class AnalysisProfile {
    private Long id;
    private String profileKey;
    private String provider;
    private String modelName;
    private String promptVersion;
    private String schemaVersion;
    private String taxonomyVersion;
    private String paramsJson;
    /** params_json 按 norm-v1 规范化后的 SHA-256（64 位十六进制） */
    private String paramsHash;
    private String normVersion;
    private Integer status;
    private String description;
    private LocalDateTime createdAt;
}
