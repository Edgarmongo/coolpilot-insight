package com.coolpilot.insight.source.domain;

import lombok.Data;

import java.time.LocalDateTime;

/** 数据源定义。能力状态以 JSON 存储，避免"能跑不等于合规可用"被写死在代码里。 */
@Data
public class DataSource {
    private Long id;
    private String code;
    private String name;
    private String sourceType;
    private String adapterCode;
    /** ENABLED / DISABLED */
    private String status;
    /** {"comments":true,"keywordSearch":false,"commentStatus":"AVAILABLE"} */
    private String capabilityJson;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long tenantId;
}
