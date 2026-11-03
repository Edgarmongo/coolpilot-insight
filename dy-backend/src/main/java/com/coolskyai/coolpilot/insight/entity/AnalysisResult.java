package com.coolskyai.coolpilot.insight.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("analysis_result")
public class AnalysisResult {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String videoId;
    private Integer totalComments;
    private Integer totalUsers;
    private Long totalLikes;
    private BigDecimal avgLikes;
    private String regionStats;
    private String sentimentStats;
    private String dailyStats;
    private String topComments;
    private LocalDateTime analyzedAt;
}
