package com.coolskyai.coolpilot.insight.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("usage_record")
public class UsageRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** AI_ANALYSIS / COLLECTION */
    private String bizType;
    private Long bizRefId;
    private Long batchId;
    private Long ownerUserId;
    private String provider;
    private String modelName;
    private Integer inputTokens;
    private Integer outputTokens;
    private BigDecimal costAmount;
    private String priceVersion;
    private Integer latencyMs;
    /** OK / ERROR / TIMEOUT */
    private String status;
    private LocalDateTime createdAt;
}
