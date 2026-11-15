package com.coolpilot.insight.source.dto;

import lombok.Data;

import java.util.Map;

/** 数据源视图：能力状态解析为对象，前端据此禁用不可用功能。 */
@Data
public class DataSourceView {
    private Long id;
    private String code;
    private String name;
    private String sourceType;
    private String adapterCode;
    private String status;
    private Map<String, Object> capability;
    private String remark;
}
