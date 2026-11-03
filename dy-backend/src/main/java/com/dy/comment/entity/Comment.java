package com.dy.comment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("comment")
public class Comment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String videoId;
    private String nickname;
    /** 评论者抖音号（unique_id） */
    private String douyinId;
    /** 评论者抖音 UID */
    private String douyinUid;
    /** 评论者安全 UID（拼接主页链接用） */
    private String secUid;
    /** IP 归属地区（抖音仅返回归属地标签，不返回真实 IP） */
    private String region;
    private LocalDateTime publishTime;
    private String content;
    private Integer likes;
    private Integer sentiment;
    private LocalDateTime createdAt;
}
