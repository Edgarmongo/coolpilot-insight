package com.coolskyai.coolpilot.insight.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("collection_checkpoint")
public class CollectionCheckpoint {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskItemId;
    private String cursorValue;
    private Integer pageNo;
    private String lastCid;
    private Long persistedCount;
    private LocalDateTime lastSuccessAt;
    private LocalDateTime updatedAt;
}
