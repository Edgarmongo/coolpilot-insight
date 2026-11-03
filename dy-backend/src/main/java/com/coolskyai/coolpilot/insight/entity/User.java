package com.coolskyai.coolpilot.insight.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String phone;
    /** 仅 BCrypt（ADR-005）；哨兵值表示待初始化 */
    private String passwordHash;
    /** 0=普通 1=管理员 */
    private Integer role;
    /** 1=正常 0=禁用 */
    private Integer status;
    private Integer mustChangePwd;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
