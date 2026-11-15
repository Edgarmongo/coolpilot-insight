package com.coolskyai.coolpilot.insight.service;

import java.util.List;
import java.util.Map;

public interface CollectService {

    /**
     * 提交采集任务。支持多视频（任务内串行）。
     *
     * @param videoIds    视频链接或编号列表
     * @param maxComments 单视频采集上限
     * @param collectMode online / online-login
     * @param ownerUserId 发起人
     */
    Map<String, Object> submit(List<String> videoIds, int maxComments, String collectMode, Long ownerUserId);

    /** 兼容旧前端：按视频查最近一次任务状态 */
    Map<String, String> getStatus(String videoId);

    List<Map<String, Object>> listTasks(String status, int page, int size);

    Map<String, Object> getTaskDetail(Long taskId);

    List<Map<String, Object>> getTaskLogs(Long taskId);

    void cancel(Long taskId);

    void retry(Long taskId);
}
