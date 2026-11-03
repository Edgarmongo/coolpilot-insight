package com.dy.comment.dto;

import lombok.Data;

@Data
public class CollectRequest {
    private String videoId;
    private int maxComments = 300;
    /**
     * 采集方式：
     *  - "online"   在线采集（容器内 Chrome，匿名态，默认）
     *  - "online-login"  在线采集 + 登录态（复用 chrome-data 卷里的抖音会话）
     *  - "upload"   本机导入（不采集，仅前端上传 CSV 后入库，见 /collect/upload）
     */
    private String collectMode = "online";
}
