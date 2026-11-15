package com.coolpilot.insight.system.domain.dict;

import lombok.Data;

import java.time.LocalDateTime;

/** 字典项。同一类型内 itemValue 唯一。 */
@Data
public class DictItem {
    private Long id;
    private Long typeId;
    private String itemValue;
    private String itemLabel;
    private Integer sort;
    private String status;
    private LocalDateTime createdAt;
}
