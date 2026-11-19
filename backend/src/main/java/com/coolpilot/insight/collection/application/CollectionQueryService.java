package com.coolpilot.insight.collection.application;

import com.coolpilot.insight.collection.domain.CollectionTask;
import com.coolpilot.insight.collection.domain.CollectionTaskItem;
import com.coolpilot.insight.collection.domain.CollectionTaskLog;
import com.coolpilot.insight.collection.infrastructure.persistence.CollectionTaskItemMapper;
import com.coolpilot.insight.collection.infrastructure.persistence.CollectionTaskLogMapper;
import com.coolpilot.insight.collection.infrastructure.persistence.CollectionTaskMapper;
import com.coolpilot.insight.common.exception.BizException;
import com.coolpilot.insight.common.exception.ErrorCode;
import com.coolpilot.insight.service.VideoService;
import com.coolpilot.insight.system.application.PermissionService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 采集任务查询（只读）。
 *
 * <p>从 {@code CollectServiceImpl} 抽出：原类同时承担状态机、调度、落盘与查询四件事，
 * 其中查询的变更原因（页面要看什么字段）与其他三者完全不同。
 *
 * <p>可见性规则集中在此：管理员看全部，普通用户只看自己发起的任务。
 */
@Service
public class CollectionQueryService {

    private final CollectionTaskMapper taskMapper;
    private final CollectionTaskItemMapper itemMapper;
    private final CollectionTaskLogMapper logMapper;
    private final VideoService videoService;
    private final PermissionService permission;

    public CollectionQueryService(CollectionTaskMapper taskMapper,
                                  CollectionTaskItemMapper itemMapper,
                                  CollectionTaskLogMapper logMapper,
                                  VideoService videoService,
                                  PermissionService permission) {
        this.taskMapper = taskMapper;
        this.itemMapper = itemMapper;
        this.logMapper = logMapper;
        this.videoService = videoService;
        this.permission = permission;
    }

    /** 某外部视频最近一次采集状态。 */
    public Map<String, String> statusOf(String externalVideoId) {
        Map<String, String> out = new LinkedHashMap<>();
        Long vid = videoService.resolveInternalId(externalVideoId);
        if (vid == null) {
            out.put("status", "IDLE");
            return out;
        }
        CollectionTaskItem item = itemMapper.selectLatestByVideo(vid);
        if (item == null) {
            out.put("status", "IDLE");
            return out;
        }
        CollectionTask task = taskMapper.selectById(item.getTaskId());
        out.put("status", item.getStatus());
        out.put("taskStatus", task == null ? "" : task.getStatus());
        out.put("taskId", String.valueOf(item.getTaskId()));
        out.put("collected", String.valueOf(item.getCollectedCount()));
        out.put("error", item.getErrorMessage() == null ? "" : item.getErrorMessage());
        return out;
    }

    public List<Map<String, Object>> listTasks(String status, int page, int size) {
        int limit = Math.min(Math.max(size, 1), 200);
        int offset = Math.max(page - 1, 0) * Math.max(size, 1);
        Long owner = permission.isAdmin() ? null : permission.currentUserId();
        return taskMapper.selectPage(status, owner, limit, offset).stream()
                .map(CollectionQueryService::taskRow).collect(Collectors.toList());
    }

    public Map<String, Object> taskDetail(Long taskId) {
        CollectionTask task = requireVisible(taskId);
        Map<String, Object> out = taskRow(task);
        out.put("params", task.getParamsJson());

        List<Map<String, Object>> items = new ArrayList<>();
        for (CollectionTaskItem item : itemMapper.selectByTask(taskId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", item.getId());
            m.put("videoId", videoService.externalId(item.getVideoId()));
            m.put("status", item.getStatus());
            m.put("collectedCount", item.getCollectedCount());
            m.put("retryCount", item.getRetryCount());
            m.put("errorMessage", item.getErrorMessage());
            m.put("startedAt", item.getStartedAt());
            m.put("finishedAt", item.getFinishedAt());
            items.add(m);
        }
        out.put("items", items);
        return out;
    }

    public List<Map<String, Object>> taskLogs(Long taskId) {
        requireVisible(taskId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (CollectionTaskLog l : logMapper.selectByTaskLimit(taskId, 500)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.getId());
            m.put("itemId", l.getTaskItemId());
            m.put("eventType", l.getEventType());
            m.put("eventLevel", l.getEventLevel());
            m.put("message", l.getMessage());
            m.put("createdAt", l.getCreatedAt());
            out.add(m);
        }
        return out;
    }

    /** 任务存在性与可见性校验，查询与操作共用同一规则。 */
    public CollectionTask requireVisible(Long taskId) {
        CollectionTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务不存在");
        }
        if (!permission.isAdmin() && !Objects.equals(task.getOwnerUserId(), permission.currentUserId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权查看该任务");
        }
        return task;
    }

    static Map<String, Object> taskRow(CollectionTask t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("taskNo", t.getTaskNo());
        m.put("taskType", t.getTaskType());
        m.put("status", t.getStatus());
        m.put("totalItems", t.getTotalItems());
        m.put("successItems", t.getSuccessItems());
        m.put("failedItems", t.getFailedItems());
        m.put("createdAt", t.getCreatedAt());
        m.put("startedAt", t.getStartedAt());
        m.put("finishedAt", t.getFinishedAt());
        return m;
    }
}
