package com.coolskyai.coolpilot.insight.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("keyword_video")
public class KeywordVideo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long keywordId;
    private Long videoId;
    private LocalDateTime discoveredAt;
}
