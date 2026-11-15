package com.coolpilot.insight.system.dto.menu;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 菜单树节点。 */
@Data
public class MenuNode {
    private Long id;
    private Long parentId;
    private String name;
    private String path;
    private String icon;
    private String menuType;
    private String permissionCode;
    private Integer sort;
    private String status;
    private List<MenuNode> children = new ArrayList<>();
}
