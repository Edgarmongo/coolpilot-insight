package com.coolpilot.insight.system.domain.menu;

import lombok.Data;

import java.time.LocalDateTime;

/** 菜单。parentId=0 为一级；status=PLANNED 表示规划中（前端置灰）。 */
@Data
public class Menu {
    private Long id;
    private Long parentId;
    private String name;
    private String path;
    private String icon;
    /** GROUP / PAGE / ACTION */
    private String menuType;
    /** 有值时需该权限才显示 */
    private String permissionCode;
    private Integer sort;
    private Integer visible;
    /** ENABLED / PLANNED */
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
