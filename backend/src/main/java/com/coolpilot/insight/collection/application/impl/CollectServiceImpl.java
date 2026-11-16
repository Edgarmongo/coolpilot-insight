package com.coolpilot.insight.collection.application.impl;

import com.coolpilot.insight.collection.domain.CollectionTask;
import com.coolpilot.insight.collection.domain.CollectionTaskItem;
import com.coolpilot.insight.collection.domain.CollectionTaskLog;
import com.coolpilot.insight.collection.domain.CollectionCheckpoint;

import com.alibaba.fastjson2.JSON;
import com.coolpilot.insight.integration.collection.CollectionClient;
import com.coolpilot.insight.integration.common.CommandResult;
import com.coolpilot.insight.integration.config.AppProperties;
import com.coolpilot.insight.collection.infrastructure.persistence.CollectionTaskMapper;
import com.coolpilot.insight.collection.infrastructure.persistence.CollectionTaskItemMapper;
import com.coolpilot.insight.collection.infrastructure.persistence.CollectionTaskLogMapper;
import com.coolpilot.insight.collection.infrastructure.persistence.CollectionCheckpointMapper;
import com.coolpilot.insight.entity.*;
import com.coolpilot.insight.mapper.*;
import com.coolpilot.insight.service.CleanService;
import com.coolpilot.insight.collection.application.CollectService;
import com.coolpilot.insight.collection.application.CollectionQueryService;
import com.coolpilot.insight.service.VideoService;
import com.coolpilot.insight.utils.CollectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.coolpilot.insight.system.application.AuditService;
import com.coolpilot.insight.system.application.PermissionService;
import com.coolpilot.insight.common.exception.BizException;
import com.coolpilot.insight.common.exception.ErrorCode;
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
    private com.coolpilot.insight.system.infrastructure.persistence.UserMapper userMapper;
    @Autowired
    private VideoService videoService;
    @Autowired
    private CleanService cleanService;
    @Autowired
    private AuditService auditService;
    @Autowired
    private PermissionService permission;
    @Autowired
    private AppProperties appProperties;
    @Autowired
    private CollectionClient collectionClient;
    @Autowired
    private com.coolpilot.insight.collection.application.CollectionAdmissionGuard admissionGuard;
    @Autowired
    private CollectionQueryService queryService;
    @Autowired
    private com.coolpilot.insight.collection.application.RunApplicationService runApplicationService;
    @Autowired
    private com.coolpilot.insight.collection.infrastructure.persistence.ColRunMapper runMapper;

    /**
     * 本编排链路只处理抖音。准入需要平台码，而 {@code extractVideoId} 也只认抖音链接，
     * 所以这里用常量而不是从入参推——推不出来才是事实。
     * 支持多平台时，source 必须随请求带进来，不能靠猜。
     */
    private static final String SOURCE_DOUYIN = "DOUYIN";
    private static final String CAPABILITY_COMMENT = "COMMENT";

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
            throw new BizException(ErrorCode.PARAM_INVALID, "请至少提供一个视频链接或编号");
        }
        // 准入必须在任何写入之前：任务不建、item 不插、HTTP 不发。
        // 放在这里而不是逐条判断，是为了让"整批不可执行"一次说清楚，
        // 而不是建出一堆必然失败的任务再逐个报错。
        admissionGuard.requireExecutable(SOURCE_DOUYIN, CAPABILITY_COMMENT);
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
            throw new BizException(ErrorCode.PARAM_INVALID, "没有可用的视频编号：" + String.join("；", rejected));
        }

        List<Long> accepted = new ArrayList<>();
        List<String> alreadyRunning = new ArrayList<>();
        for (String awemeId : awemeIds) {
            Video v = videoService.requireOrCreate(awemeId);
            Long running = itemMapper.countActiveByVideo(v.getId());
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
        // C-01：Task 只有 IDLE/RUNNING/PAUSED/DISABLED，PENDING 属于 Run
        task.setStatus("IDLE");
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
        // 领取单位从 Task 改为 Run（C-03）：Task 只是定义态，PENDING 只属于 Run
        com.coolpilot.insight.collection.domain.ColRun candidate = runMapper.selectPendingOldest();
        if (candidate == null) {
            return;
        }
        int claimed = runMapper.claimPending(candidate.getId());
        if (claimed != 1) {
            return;
        }
        writeLog(candidate.getTaskId(), null, "STATUS_CHANGE", "INFO",
                "运行 #" + candidate.getRunNo() + " 开始执行", null);
        busy.set(true);
        worker.submit(() -> {
            try {
                int[] r = runTask(candidate.getTaskId());
                runApplicationService.finish(candidate.getId(), deriveRunStatus(r), r[0], r[1], null);
            } catch (Exception e) {
                log.error("[collect] 运行 {} 执行异常", candidate.getId(), e);
                runApplicationService.finish(candidate.getId(), "FAILED", 0, 0,
                        String.valueOf(e.getMessage()));
            } finally {
                busy.set(false);
            }
        });
    }

    /**
     * 清扫僵尸 Run：worker 崩溃或进程重启会让 Run 永远停在 RUNNING，
     * 而 Task 的 claimIdle 要求 IDLE —— 结果是该任务再也无法触发。
     *
     * <p>超过 30 分钟仍 RUNNING 视为异常终止（单次采集超时上限 10 分钟），
     * 置 FAILED 并把 Task 释放回 IDLE。
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
    public void sweepStaleRuns() {
        try {
            for (com.coolpilot.insight.collection.domain.ColRun r
                    : runMapper.selectStaleRunning(30)) {
                if (runMapper.markFailed(r.getId(), "worker interrupted or timeout") == 1) {
                    taskMapper.releaseToIdle(r.getTaskId(), "IDLE");
                    writeLog(r.getTaskId(), null, "STATUS_CHANGE", "WARN",
                            "运行 #" + r.getRunNo() + " 超时，已标记失败", null);
                    log.warn("[collect] 清扫僵尸 run id={} task={}", r.getId(), r.getTaskId());
                }
            }
        } catch (Exception e) {
            log.error("[collect] 僵尸 run 清扫失败", e);
        }
    }

    /** 计数 → Run 终态。取消不算失败（裁决 DECISION-02 §3）。 */
    private String deriveRunStatus(int[] r) {
        int success = r[0];
        int failed = r[1];
        int cancelled = r[2];
        if (cancelled > 0) {
            return "CANCELLED";
        }
        if (failed == 0) {
            return "COMPLETED";
        }
        return success == 0 ? "FAILED" : "PARTIAL_SUCCESS";
    }

    private int[] runTask(Long taskId) {
        CollectionTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return new int[]{0, 0, 0};
        }
        Map<String, Object> params = paramsOf(task);
        int maxComments = params.get("maxComments") == null ? 300
                : ((Number) params.get("maxComments")).intValue();
        String mode = String.valueOf(params.getOrDefault("collectMode", "online"));

        List<CollectionTaskItem> items = itemMapper.selectByTask(taskId);

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

        // 结果只写回 statistics 字段；终态由 Run 承载（Task 不得被 Run 结果污染）
        CollectionTask up = new CollectionTask();
        up.setId(taskId);
        up.setSuccessItems(success);
        up.setFailedItems(failed);
        taskMapper.updateById(up);
        writeLog(taskId, null, "STATUS_CHANGE", "INFO",
                "本次运行结束：成功 " + success + " / 失败 " + failed, null);
        return new int[]{success, failed, cancelled};
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
            com.coolpilot.insight.common.security.RequestContext.setUserId(ownerUserId);
            com.coolpilot.insight.system.domain.User owner = ownerUserId == null ? null : userMapper.selectById(ownerUserId);
            com.coolpilot.insight.common.security.RequestContext.setRole(
                    owner == null || owner.getRole() == null ? 0 : owner.getRole());
            return cleanService.runClean(Collections.singletonList(fileName), ownerUserId);
        } finally {
            com.coolpilot.insight.common.security.RequestContext.clear();
        }
    }

    private void saveCheckpoint(Long itemId, long persisted) {        CollectionCheckpoint exist = checkpointMapper.selectByTaskItemId(itemId);
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
                                    Long taskId, Long itemId) {
        // 子进程调用已抽到 integration 层：本方法只关心"成功与否"这一业务结论
        CommandResult result = collectionClient.collectComments(
                awemeId, maxComments, "online-login".equals(mode), taskId, itemId);
        return result.success();
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
        return queryService.statusOf(videoId);
    }

    @Override
    public List<Map<String, Object>> listTasks(String status, int page, int size) {
        return queryService.listTasks(status, page, size);
    }

    

    @Override
    public Map<String, Object> getTaskDetail(Long taskId) {
        return queryService.taskDetail(taskId);
    }

    @Override
    public List<Map<String, Object>> getTaskLogs(Long taskId) {
        return queryService.taskLogs(taskId);
    }

    @Override
    public void cancel(Long taskId) {
        CollectionTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务不存在");
        }
        if (!permission.isAdmin() && !Objects.equals(task.getOwnerUserId(), permission.currentUserId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权取消该任务");
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
            throw new BizException(ErrorCode.STATE_CONFLICT, "任务已结束，无法取消");
        }
        writeLog(taskId, null, "CANCEL", "WARN", "用户请求取消任务", null);
        auditService.audit("CANCEL_COLLECTION_TASK", "COLLECTION_TASK", taskId, null);
    }

    @Override
    public void retry(Long taskId) {
        CollectionTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务不存在");
        }
        if (!permission.isAdmin() && !Objects.equals(task.getOwnerUserId(), permission.currentUserId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权重试该任务");
        }
        if (!"FAILED".equals(task.getStatus()) && !"PARTIAL_SUCCESS".equals(task.getStatus())) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "只有失败或部分成功的任务可以重试");
        }
        List<CollectionTaskItem> failedItems = itemMapper.selectByTaskAndStatus(taskId, "FAILED");
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
        auditService.audit("RETRY_COLLECTION_TASK", "COLLECTION_TASK", taskId, null);
    }

    private void releaseActiveFlags(Long taskId) {
        for (CollectionTaskItem item : itemMapper.selectByTask(taskId)) {
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
        itemMapper.clearActiveFlag(itemId);
    }

    @Override
    public List<com.coolpilot.insight.collection.domain.ColRun> listRuns(Long taskId, int page, int size) {
        int limit = Math.min(Math.max(size, 1), 100);
        int offset = Math.max(page - 1, 0) * Math.max(size, 1);
        return runMapper.selectPageByTask(taskId, offset, limit);
    }

    @Override
    public com.coolpilot.insight.collection.domain.ColRun runDetail(Long runId) {
        var run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "运行记录不存在");
        }
        return run;
    }
}
