package com.coolpilot.insight.collection.application;

import com.coolpilot.insight.collection.domain.ColRun;
import com.coolpilot.insight.collection.domain.CollectionTask;
import com.coolpilot.insight.collection.infrastructure.persistence.ColRunMapper;
import com.coolpilot.insight.collection.infrastructure.persistence.CollectionTaskMapper;
import com.coolpilot.insight.common.exception.BizException;
import com.coolpilot.insight.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Run 创建的唯一入口（手动与定时共用）。
 *
 * <p>并发保护不依赖 JVM 锁：用 {@code collection_task.status} 的条件更新做原子闸门——
 * {@code UPDATE ... WHERE id=? AND status='IDLE'} 只有一方拿到 affected=1，
 * 另一方直接 409。这样即使多实例部署也成立，且不需要新增字段或索引。
 *
 * <p>状态联动：创建 Run 时 Task → RUNNING；Run 终结时由 {@link #finish} 回落到
 * IDLE 或 PAUSED（取决于任务是否被暂停），**绝不把 Run 的 FAILED 写进 Task**。
 */
@Service
public class RunApplicationService {

    private static final Logger log = LoggerFactory.getLogger(RunApplicationService.class);

    private final ColRunMapper runMapper;
    private final CollectionTaskMapper taskMapper;

    public RunApplicationService(ColRunMapper runMapper, CollectionTaskMapper taskMapper) {
        this.runMapper = runMapper;
        this.taskMapper = taskMapper;
    }

    /**
     * 手动/定时触发一次运行。
     *
     * @param triggerType MANUAL 或 SCHEDULED
     * @param triggerUser 手动触发者 id；定时为 null
     */
    @Transactional
    public ColRun trigger(Long taskId, String triggerType, Long triggerUser) {
        CollectionTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务不存在");
        }
        if ("DISABLED".equals(task.getStatus())) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "任务已禁用，不能触发");
        }
        if ("PAUSED".equals(task.getStatus())) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "任务已暂停，请先恢复");
        }

        // 原子闸门：只有 IDLE → RUNNING 成功的那一方可以继续
        int claimed = taskMapper.claimIdle(taskId);
        if (claimed != 1) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "该任务已有进行中的运行，请稍后查看运行记录");
        }

        ColRun run = new ColRun();
        run.setTaskId(taskId);
        run.setRunNo(runMapper.maxRunNo(taskId) + 1);
        run.setTriggerType(triggerType == null ? "MANUAL" : triggerType);
        run.setStatus("PENDING");
        run.setConfigSnapshot(buildSnapshot(task, triggerType, triggerUser));

        try {
            runMapper.insert(run);
        } catch (DuplicateKeyException e) {
            // run_no 并发撞车（极端情况），事务回滚，Task 状态一并回退
            throw new BizException(ErrorCode.STATE_CONFLICT, "运行号冲突，请重试");
        }
        return runMapper.selectById(run.getId());
    }

    /**
     * Run 终结后回写并联动 Task。
     *
     * <p>Task 回落规则：曾被 PAUSED 的回到 PAUSED，否则 IDLE。
     * 依据是 Task 自身的启停意图，而不是 Run 的结果。
     */
    @Transactional
    public void finish(Long runId, String runStatus, int success, int failed, String errorSummary) {
        ColRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "运行记录不存在");
        }
        if (!"RUNNING".equals(run.getStatus()) && !"PENDING".equals(run.getStatus())) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "该运行已结束，不能再次结束");
        }

        ColRun up = new ColRun();
        up.setId(runId);
        up.setStatus(runStatus);
        up.setSuccessCount(success);
        up.setFailedCount(failed);
        up.setTotalExecutionCount(success + failed);
        up.setErrorSummary(errorSummary);
        up.setFinishedAt(LocalDateTime.now());
        runMapper.updateResult(up);

        // Task 只回落到 IDLE / PAUSED，不复制 Run 结果
        CollectionTask task = taskMapper.selectById(run.getTaskId());
        if (task != null) {
            // 依据 Task 自身的启停意图回落：暂停过的回 PAUSED，否则 IDLE
            String next = "PAUSED".equals(task.getStatus()) ? "PAUSED" : "IDLE";
            taskMapper.releaseToIdle(run.getTaskId(), next);
        }
    }

    public ColRun latestOf(Long taskId) {
        return runMapper.selectLatestByTask(taskId);
    }

    private String buildSnapshot(CollectionTask task, String triggerType, Long triggerUser) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("schemaVersion", 1);
        snap.put("triggerType", triggerType);
        snap.put("triggerUser", triggerUser);
        snap.put("taskNo", task.getTaskNo());
        snap.put("taskType", task.getTaskType());
        // 参数快照：原样固化，不伪造当前不存在的策略/账号信息
        snap.put("params", task.getParamsJson());
        snap.put("sourceId", task.getSourceId());
        snap.put("capturedAt", LocalDateTime.now().toString());
        return com.alibaba.fastjson2.JSON.toJSONString(snap);
    }
}
