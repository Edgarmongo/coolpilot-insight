package com.coolskyai.coolpilot.insight.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("keyword")
public class Keyword {
    @TableId(type = IdType.AUTO)
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
