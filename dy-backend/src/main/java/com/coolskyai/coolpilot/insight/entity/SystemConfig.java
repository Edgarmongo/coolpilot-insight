package com.coolskyai.coolpilot.insight.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("system_config")
public class SystemConfig {
    @TableId(type = IdType.AUTO)
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
