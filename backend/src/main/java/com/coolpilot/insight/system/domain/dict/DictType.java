package com.coolpilot.insight.system.domain.dict;

import lombok.Data;

import java.time.LocalDateTime;

/** 字典类型。 */
@Data
public class DictType {
    private Long id;
    private String typeCode;
    private String typeName;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
