package com.coolskyai.coolpilot.insight.dto;

import lombok.Data;

import java.util.List;

@Data
public class CollectRequest {
    /** 单个视频（兼容旧前端） */
    private String videoId;
    /** 多视频（优先于 videoId） */
    private List<String> videoIds;
    private int maxComments = 300;
    /**
     * 采集方式：
     *  - "online"        在线采集（容器内 Chrome，匿名态，默认）
     *  - "online-login"  在线采集 + 登录态（复用 chrome-data 卷里的抖音会话）
     */
    private String collectMode = "online";
}
