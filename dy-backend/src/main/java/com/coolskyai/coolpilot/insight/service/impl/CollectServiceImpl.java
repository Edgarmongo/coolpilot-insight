package com.coolskyai.coolpilot.insight.service.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.coolskyai.coolpilot.insight.config.AppProperties;
import com.coolskyai.coolpilot.insight.entity.*;
import com.coolskyai.coolpilot.insight.mapper.*;
import com.coolskyai.coolpilot.insight.service.CleanService;
import com.coolskyai.coolpilot.insight.service.CollectService;
import com.coolskyai.coolpilot.insight.service.GovernanceService;
import com.coolskyai.coolpilot.insight.service.VideoService;
import com.coolskyai.coolpilot.insight.utils.CollectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 采集任务中心。
 *
 * <p>调度模型（第一阶段，按蓝图 4.3）：
 * 单 JVM、单 Worker、单 Chrome、任务内视频串行。
 * 领取任务用条件更新实现原子性，租约 + 心跳防止任务被永久占用。
 */
@Service
public class CollectServiceImpl implements CollectService {

    private static final Logger log = LoggerFactory.getLogger(CollectServiceImpl.class);
    private static final int LEASE_MINUTES = 15;

    @Autowired
    private CollectionTaskMapper taskMapper;
    @Autowired
    private CollectionTaskItemMapper itemMapper;
    @Autowired
    private CollectionTaskLogMapper logMapper;
    @Autowired
    private CollectionCheckpointMapper checkpointMapper;
    @Autowired
    private CommentMapper commentMapper;
    @Autowired
    private com.coolskyai.coolpilot.insight.mapper.UserMapper userMapper;
    @Autowired
    private VideoService videoService;
    @Autowired
    private CleanService cleanService;
    @Autowired
    private GovernanceService governance;
    @Autowired
    private AppProperties appProperties;

    /** 同一时刻只允许一个采集批次在跑（Chrome 单实例限制） */
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "collect-worker");
        t.setDaemon(true);
        return t;
    });

    // ================================================================ 提交

    @Override
    public Map<String, Object> submit(List<String> videoIds, int maxComments, String collectMode, Long ownerUserId) {
        if (videoIds == null || videoIds.isEmpty()) {
            throw new IllegalArgumentException("请至少提供一个视频链接或编号");
        }
        int max = Math.min(Math.max(maxComments, 1), 1000);

        // 解析 + 去重，保持输入顺序
        LinkedHashSet<String> awemeIds = new LinkedHashSet<>();
        List<String> rejected = new ArrayList<>();
        for (String raw : videoIds) {
            try {
                String id = CollectUtils.extractVideoId(raw);
                if (id.contains("douyin.com")) {
                    rejected.add(raw + "（短链需先展开）");
                } else {
                    awemeIds.add(id);
                }
            } catch (Exception e) {
                rejected.add(raw + "（" + e.getMessage() + "）");
            }
        }
        if (awemeIds.isEmpty()) {
            throw new IllegalArgumentException("没有可用的视频编号：" + String.join("；", rejected));
        }

        List<Long> accepted = new ArrayList<>();
        List<String> alreadyRunning = new ArrayList<>();
        for (String awemeId : awemeIds) {
            Video v = videoService.requireOrCreate(awemeId);
            Long running = itemMapper.selectCount(new LambdaQueryWrapper<CollectionTaskItem>()
                    .eq(CollectionTaskItem::getVideoId, v.getId())
                    .eq(CollectionTaskItem::getActiveFlag, 1));
            if (running != null && running > 0) {
                alreadyRunning.add(awemeId);
                continue;
            }
            accepted.add(v.getId());
        }
        if (accepted.isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("taskId", null);
            out.put("message", "这些视频已有采集任务在排队或执行中，请稍后查看任务中心");
            out.put("alreadyRunning", alreadyRunning);
            out.put("rejected", rejected);
            return out;
        }

        CollectionTask task = new CollectionTask();
        task.setTaskNo(nextTaskNo("CT"));
        task.setTaskType("COLLECTION");
        task.setStatus("PENDING");
        task.setOwnerUserId(ownerUserId);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("maxComments", max);
        params.put("collectMode", collectMode == null ? "online" : collectMode);
        params.put("requested", awemeIds);
        task.setParamsJson(JSON.toJSONString(params));
        task.setTotalItems(accepted.size());
        task.setSuccessItems(0);
        task.setFailedItems(0);
        taskMapper.insert(task);

        for (Long vid : accepted) {
            CollectionTaskItem item = new CollectionTaskItem();
            item.setTaskId(task.getId());
            item.setVideoId(vid);
            item.setStatus("PENDING");
            item.setActiveFlag(1);
            item.setRetryCount(0);
            item.setMaxRetry(2);
            item.setRequestedMax(max);
            item.setCollectedCount(0L);
            item.setSkippedCount(0L);
            itemMapper.insert(item);
        }
        writeLog(task.getId(), null, "STATUS_CHANGE", "INFO", "任务已创建，共 " + accepted.size() + " 个视频", null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", task.getId());
        out.put("taskNo", task.getTaskNo());
        out.put("totalItems", accepted.size());
        out.put("alreadyRunning", alreadyRunning);
        out.put("rejected", rejected);
        out.put("message", "任务已提交，将在后台串行执行");
        return out;
    }

    private String nextTaskNo(String prefix) {
        return prefix + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%03d", new Random().nextInt(1000));
    }

    // ================================================================ 调度

    @Scheduled(fixedDelay = 5000, initialDelay = 15000)
    public void dispatch() {
        if (busy.get()) {
            return;
        }
        CollectionTask candidate = taskMapper.selectOne(new LambdaQueryWrapper<CollectionTask>()
                .eq(CollectionTask::getStatus, "PENDING")
                .orderByAsc(CollectionTask::getId).last("LIMIT 1"));
        if (candidate == null) {
            return;
        }
        // 条件更新实现原子领取：只有一个实例能把它从 PENDING 改成 RUNNING
        CollectionTask claim = new CollectionTask();
        claim.setId(candidate.getId());
        claim.setStatus("RUNNING");
        claim.setStartedAt(LocalDateTime.now());
        int affected = taskMapper.update(claim, new LambdaQueryWrapper<CollectionTask>()
                .eq(CollectionTask::getId, candidate.getId())
                .eq(CollectionTask::getStatus, "PENDING"));
        if (affected != 1) {
            return;
        }
        writeLog(candidate.getId(), null, "STATUS_CHANGE", "INFO", "任务开始执行", null);
        busy.set(true);
        worker.submit(() -> {
            try {
                runTask(candidate.getId());
            } catch (Exception e) {
                log.error("[collect] 任务 {} 执行异常", candidate.getId(), e);
                finishWithError(candidate.getId(), e.getMessage());
            } finally {
                busy.set(false);
            }
        });
    }

    private void runTask(Long taskId) {
        CollectionTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        Map<String, Object> params = paramsOf(task);
        int maxComments = params.get("maxComments") == null ? 300
                : ((Number) params.get("maxComments")).intValue();
        String mode = String.valueOf(params.getOrDefault("collectMode", "online"));

        List<CollectionTaskItem> items = itemMapper.selectList(new LambdaQueryWrapper<CollectionTaskItem>()
                .eq(CollectionTaskItem::getTaskId, taskId)
                .orderByAsc(CollectionTaskItem::getId));

        int success = 0;
        int failed = 0;
        int cancelled = 0;

        for (CollectionTaskItem item : items) {
            CollectionTask fresh = taskMapper.selectById(taskId);
            if (fresh != null && "CANCEL_REQUESTED".equals(fresh.getStatus())) {
                cancelled++;
                markItem(item, "CANCELLED", null, "任务被取消");
                continue;
            }
            if (!"PENDING".equals(item.getStatus()) && !"RETRY_WAIT".equals(item.getStatus())) {
                if ("SUCCESS".equals(item.getStatus())) {
                    success++;
                } else if ("FAILED".equals(item.getStatus())) {
                    failed++;
                }
                continue;
            }
            boolean ok = runItem(task, item, maxComments, mode);
            if (ok) {
                success++;
            } else {
                failed++;
            }
        }

        CollectionTask up = new CollectionTask();
        up.setId(taskId);
        up.setSuccessItems(success);
        up.setFailedItems(failed);
        up.setFinishedAt(LocalDateTime.now());
        if (cancelled > 0) {
            up.setStatus("CANCELLED");
        } else if (failed == 0) {
            up.setStatus("SUCCESS");
        } else if (success == 0) {
            up.setStatus("FAILED");
        } else {
            up.setStatus("PARTIAL_SUCCESS");
        }
        taskMapper.updateById(up);
        writeLog(taskId, null, "STATUS_CHANGE", "INFO",
                "任务结束：成功 " + success + " / 失败 " + failed, null);
    }

    private boolean runItem(CollectionTask task, CollectionTaskItem item, int maxComments, String mode) {
        Video video = videoService.findById(item.getVideoId());
        if (video == null) {
            markItem(item, "FAILED", "VIDEO_MISSING", "视频记录不存在");
            return false;
        }
        String awemeId = video.getAwemeId();

        // 领取子任务：租约 + 心跳
        CollectionTaskItem run = new CollectionTaskItem();
        run.setId(item.getId());
        run.setStatus("RUNNING");
        run.setWorkerId("worker-" + System.currentTimeMillis() % 100000);
        run.setStartedAt(LocalDateTime.now());
        run.setHeartbeatAt(LocalDateTime.now());
        run.setLeaseUntil(LocalDateTime.now().plusMinutes(LEASE_MINUTES));
        itemMapper.updateById(run);
        writeLog(task.getId(), item.getId(), "STATUS_CHANGE", "INFO", "开始采集 " + awemeId, null);

        boolean ok = false;
        String error = null;
        try {
            ok = invokeCollector(awemeId, maxComments, mode, task.getId(), item.getId());
            if (ok) {
                File csv = new File(appProperties.getDatasDir(), awemeId + ".csv");
                if (!csv.exists() || csv.length() == 0) {
                    ok = false;
                    error = "采集未产出数据文件（可能触发风控或该视频无评论）";
                } else {
                    Map<String, Object> cleanResult = runCleanAs(
                            task.getOwnerUserId(), awemeId + ".csv");
                    if (!Boolean.TRUE.equals(cleanResult.get("success"))) {
                        ok = false;
                        error = "清洗入库失败：" + cleanResult.get("output");
                    }
                }
            } else {
                error = "采集进程返回失败";
            }
        } catch (Exception e) {
            error = e.getMessage();
            log.warn("[collect] 子任务 {} 异常: {}", item.getId(), e.getMessage());
        }

        if (ok) {
            Map<String, Object> row = commentMapper.summaryRow(item.getVideoId());
            long total = row == null || row.get("total") == null ? 0 : ((Number) row.get("total")).longValue();
            CollectionTaskItem done = new CollectionTaskItem();
            done.setId(item.getId());
            done.setStatus("SUCCESS");
            done.setCollectedCount(total);
            done.setFinishedAt(LocalDateTime.now());
            itemMapper.updateById(done);
            clearActiveFlag(item.getId());
            // 断点：记录已入库数量
            saveCheckpoint(item.getId(), total);
            writeLog(task.getId(), item.getId(), "PAGE_DONE", "INFO", "采集完成，库内累计 " + total + " 条", null);
        } else {
            markItem(item, "FAILED", "COLLECT_FAILED", error);
        }
        return ok;
    }

    /**
     * 以任务发起人的身份执行清洗。
     *
     * <p>调度线程没有 HTTP 请求上下文，若不显式注入身份，
     * 统一授权判定会认为「无用户 → 无权限」，导致采集成功但入库被拒。
     * 这里复用同一套 RequestContext，而不是绕过校验。
     */
    private Map<String, Object> runCleanAs(Long ownerUserId, String fileName) {
        try {
            com.coolskyai.coolpilot.insight.interceptor.RequestContext.setUserId(ownerUserId);
            com.coolskyai.coolpilot.insight.entity.User owner = ownerUserId == null ? null : userMapper.selectById(ownerUserId);
            com.coolskyai.coolpilot.insight.interceptor.RequestContext.setRole(
                    owner == null || owner.getRole() == null ? 0 : owner.getRole());
            return cleanService.runClean(Collections.singletonList(fileName), ownerUserId);
        } finally {
            com.coolskyai.coolpilot.insight.interceptor.RequestContext.clear();
        }
    }

    private void saveCheckpoint(Long itemId, long persisted) {        CollectionCheckpoint exist = checkpointMapper.selectOne(
                new LambdaQueryWrapper<CollectionCheckpoint>().eq(CollectionCheckpoint::getTaskItemId, itemId));
        if (exist == null) {
            CollectionCheckpoint cp = new CollectionCheckpoint();
            cp.setTaskItemId(itemId);
            cp.setPersistedCount(persisted);
            cp.setLastSuccessAt(LocalDateTime.now());
            checkpointMapper.insert(cp);
        } else {
            exist.setPersistedCount(persisted);
            exist.setLastSuccessAt(LocalDateTime.now());
            checkpointMapper.updateById(exist);
        }
    }

    private void markItem(CollectionTaskItem item, String status, String code, String message) {
        CollectionTaskItem patch = new CollectionTaskItem();
        patch.setId(item.getId());
        patch.setStatus(status);
        patch.setErrorCode(code);
        patch.setErrorMessage(message == null ? null : message.substring(0, Math.min(message.length(), 2000)));
        patch.setFinishedAt(LocalDateTime.now());
        itemMapper.updateById(patch);
        clearActiveFlag(item.getId());
        writeLog(item.getTaskId(), item.getId(), "ERROR", "FAILED".equals(status) ? "ERROR" : "WARN",
                message == null ? status : message, null);
    }

    private boolean invokeCollector(String awemeId, int maxComments, String mode,
                                    Long taskId, Long itemId) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(appProperties.getPythonExe());
        cmd.add("douyin_comment.py");
        cmd.add(awemeId);
        cmd.add(String.valueOf(maxComments));
        if ("online-login".equals(mode)) {
            cmd.add("--use-login");
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File(appProperties.getPythonDataDir()));
        pb.redirectErrorStream(true);
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        pb.environment().put("COLLECT_TASK_ID", String.valueOf(taskId));
        pb.environment().put("COLLECT_ITEM_ID", String.valueOf(itemId));
        Process process = pb.start();

        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                out.append(line).append("\n");
            }
        }
        int exit = process.waitFor();
        if (exit != 0) {
            log.warn("[collect] {} 退出码 {}，输出尾部: {}", awemeId, exit,
                    out.length() > 500 ? out.substring(out.length() - 500) : out);
            return false;
        }
        return true;
    }

    private void finishWithError(Long taskId, String message) {
        CollectionTask up = new CollectionTask();
        up.setId(taskId);
        up.setStatus("FAILED");
        up.setFinishedAt(LocalDateTime.now());
        taskMapper.updateById(up);
        writeLog(taskId, null, "ERROR", "ERROR", "任务异常终止：" + message, null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> paramsOf(CollectionTask task) {
        if (task.getParamsJson() == null || task.getParamsJson().isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            return JSON.parseObject(task.getParamsJson(), Map.class);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private void writeLog(Long taskId, Long itemId, String type, String level, String message, String details) {
        try {
            CollectionTaskLog l = new CollectionTaskLog();
            l.setTaskId(taskId);
            l.setTaskItemId(itemId);
            l.setEventType(type);
            l.setEventLevel(level);
            l.setMessage(message == null ? null : message.substring(0, Math.min(message.length(), 500)));
            l.setDetailsJson(details);
            logMapper.insert(l);
        } catch (Exception e) {
            log.warn("[collect] 日志写入失败: {}", e.getMessage());
        }
    }

    // ================================================================ 查询

    @Override
    public Map<String, String> getStatus(String videoId) {
        Map<String, String> out = new LinkedHashMap<>();
        Long vid = videoService.resolveInternalId(videoId);
        if (vid == null) {
            out.put("status", "IDLE");
            return out;
        }
        CollectionTaskItem item = itemMapper.selectOne(new LambdaQueryWrapper<CollectionTaskItem>()
                .eq(CollectionTaskItem::getVideoId, vid)
                .orderByDesc(CollectionTaskItem::getId).last("LIMIT 1"));
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

    @Override
    public List<Map<String, Object>> listTasks(String status, int page, int size) {
        LambdaQueryWrapper<CollectionTask> wrapper = new LambdaQueryWrapper<CollectionTask>()
                .orderByDesc(CollectionTask::getId);
        if (status != null && !status.isEmpty()) {
            wrapper.eq(CollectionTask::getStatus, status);
        }
        if (!governance.isAdmin()) {
            wrapper.eq(CollectionTask::getOwnerUserId, governance.currentUserId());
        }
        wrapper.last("LIMIT " + Math.min(Math.max(size, 1), 200)
                + " OFFSET " + Math.max(page - 1, 0) * Math.max(size, 1));
        return taskMapper.selectList(wrapper).stream().map(this::taskRow).collect(Collectors.toList());
    }

    private Map<String, Object> taskRow(CollectionTask t) {
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

    @Override
    public Map<String, Object> getTaskDetail(Long taskId) {
        CollectionTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在");
        }
        if (!governance.isAdmin() && !Objects.equals(task.getOwnerUserId(), governance.currentUserId())) {
            throw new SecurityException("无权查看该任务");
        }
        Map<String, Object> out = taskRow(task);
        out.put("params", task.getParamsJson());

        List<Map<String, Object>> items = new ArrayList<>();
        for (CollectionTaskItem item : itemMapper.selectList(new LambdaQueryWrapper<CollectionTaskItem>()
                .eq(CollectionTaskItem::getTaskId, taskId).orderByAsc(CollectionTaskItem::getId))) {
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

    @Override
    public List<Map<String, Object>> getTaskLogs(Long taskId) {
        CollectionTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在");
        }
        if (!governance.isAdmin() && !Objects.equals(task.getOwnerUserId(), governance.currentUserId())) {
            throw new SecurityException("无权查看该任务");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (CollectionTaskLog l : logMapper.selectList(new LambdaQueryWrapper<CollectionTaskLog>()
                .eq(CollectionTaskLog::getTaskId, taskId)
                .orderByAsc(CollectionTaskLog::getId).last("LIMIT 500"))) {
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

    @Override
    public void cancel(Long taskId) {
        CollectionTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在");
        }
        if (!governance.isAdmin() && !Objects.equals(task.getOwnerUserId(), governance.currentUserId())) {
            throw new SecurityException("无权取消该任务");
        }
        if ("PENDING".equals(task.getStatus())) {
            CollectionTask up = new CollectionTask();
            up.setId(taskId);
            up.setStatus("CANCELLED");
            up.setFinishedAt(LocalDateTime.now());
            taskMapper.updateById(up);
            releaseActiveFlags(taskId);
        } else if ("RUNNING".equals(task.getStatus())) {
            CollectionTask up = new CollectionTask();
            up.setId(taskId);
            up.setStatus("CANCEL_REQUESTED");
            taskMapper.updateById(up);
        } else {
            throw new IllegalStateException("任务已结束，无法取消");
        }
        writeLog(taskId, null, "CANCEL", "WARN", "用户请求取消任务", null);
        governance.audit("CANCEL_COLLECTION_TASK", "COLLECTION_TASK", taskId, null);
    }

    @Override
    public void retry(Long taskId) {
        CollectionTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在");
        }
        if (!governance.isAdmin() && !Objects.equals(task.getOwnerUserId(), governance.currentUserId())) {
            throw new SecurityException("无权重试该任务");
        }
        if (!"FAILED".equals(task.getStatus()) && !"PARTIAL_SUCCESS".equals(task.getStatus())) {
            throw new IllegalStateException("只有失败或部分成功的任务可以重试");
        }
        List<CollectionTaskItem> failedItems = itemMapper.selectList(new LambdaQueryWrapper<CollectionTaskItem>()
                .eq(CollectionTaskItem::getTaskId, taskId).eq(CollectionTaskItem::getStatus, "FAILED"));
        for (CollectionTaskItem item : failedItems) {
            if (item.getRetryCount() != null && item.getMaxRetry() != null
                    && item.getRetryCount() >= item.getMaxRetry()) {
                continue;
            }
            CollectionTaskItem patch = new CollectionTaskItem();
            patch.setId(item.getId());
            patch.setStatus("PENDING");
            patch.setRetryCount((item.getRetryCount() == null ? 0 : item.getRetryCount()) + 1);
            patch.setActiveFlag(1);
            patch.setErrorMessage(null);
            patch.setErrorCode(null);
            itemMapper.updateById(patch);
        }
        CollectionTask up = new CollectionTask();
        up.setId(taskId);
        up.setStatus("PENDING");
        up.setFinishedAt(null);
        up.setFailedItems(0);
        taskMapper.updateById(up);
        writeLog(taskId, null, "RETRY", "INFO", "用户触发重试", null);
        governance.audit("RETRY_COLLECTION_TASK", "COLLECTION_TASK", taskId, null);
    }

    private void releaseActiveFlags(Long taskId) {
        for (CollectionTaskItem item : itemMapper.selectList(new LambdaQueryWrapper<CollectionTaskItem>()
                .eq(CollectionTaskItem::getTaskId, taskId))) {
            if (item.getActiveFlag() != null) {
                CollectionTaskItem patch = new CollectionTaskItem();
                patch.setId(item.getId());
                patch.setStatus("CANCELLED");
                itemMapper.updateById(patch);
                clearActiveFlag(item.getId());
            }
        }
    }

    /**
     * 显式把 active_flag 置 NULL。
     *
     * <p>不能靠 {@code entity.setActiveFlag(null) + updateById} —— MyBatis-Plus 默认
     * 忽略 null 字段（NOT_NULL 策略），那样「终态释放活动标记」根本不会写库，
     * 该视频将永久无法再次采集。
     */
    private void clearActiveFlag(Long itemId) {
        itemMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<CollectionTaskItem>()
                .eq(CollectionTaskItem::getId, itemId)
                .set(CollectionTaskItem::getActiveFlag, null));
    }
}
