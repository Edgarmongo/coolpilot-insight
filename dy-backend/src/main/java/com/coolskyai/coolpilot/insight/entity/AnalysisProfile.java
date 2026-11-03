package com.coolskyai.coolpilot.insight.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("analysis_profile")
public class AnalysisProfile {
    @TableId(type = IdType.AUTO)
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
