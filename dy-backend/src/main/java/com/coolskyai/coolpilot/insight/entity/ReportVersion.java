package com.coolskyai.coolpilot.insight.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("report_version")
public class ReportVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long reportId;
    private Integer versionNo;
    /** 唯一权威内容 */
    private String contentJson;
    private String contentMd;
    private Long analysisTaskId;
    private Integer evidenceCount;
    private Long generatedBy;
    private LocalDateTime generatedAt;
}
