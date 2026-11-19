package com.coolpilot.insight.collection;

import com.coolpilot.insight.AbstractIntegrationTest;
import com.coolpilot.insight.collection.application.RunApplicationService;
import com.coolpilot.insight.collection.domain.ColRun;
import com.coolpilot.insight.collection.domain.CollectionTask;
import com.coolpilot.insight.collection.infrastructure.persistence.CollectionTaskMapper;
import com.coolpilot.insight.common.exception.BizException;
import com.coolpilot.insight.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Run 创建与 Task 状态联动（C-02 Batch 2）。 */
class RunLifecycleTest extends AbstractIntegrationTest {

    @Autowired private RunApplicationService runService;
    @Autowired private CollectionTaskMapper taskMapper;

    private CollectionTask newTask(String status) {
        CollectionTask t = new CollectionTask();
        t.setTaskNo("C2" + System.nanoTime());
        t.setTaskType("COLLECTION");
        t.setStatus(status);
        t.setOwnerUserId(1L);
        t.setTotalItems(0);
        t.setSuccessItems(0);
        t.setFailedItems(0);
        t.setParamsJson("{\"maxComments\":10,\"collectMode\":\"online\"}");
        taskMapper.insert(t);
        return t;
    }

    @Test
    @DisplayName("IDLE 任务可触发，Task 转 RUNNING，快照与 run_no 正确")
    void triggerCreatesRun() {
        CollectionTask t = newTask("IDLE");

        ColRun run = runService.trigger(t.getId(), "MANUAL", 1L);

        assertThat(run.getId()).isNotNull();
        assertThat(run.getRunNo()).isEqualTo(1);
        assertThat(run.getStatus()).isEqualTo("PENDING");
        assertThat(run.getTriggerType()).isEqualTo("MANUAL");
        assertThat(run.getConfigSnapshot()).contains("maxComments").contains("MANUAL");
        assertThat(taskMapper.selectById(t.getId()).getStatus()).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("同一 Task 重复触发被拒（已有活动 Run）")
    void duplicateTriggerRejected() {
        CollectionTask t = newTask("IDLE");
        runService.trigger(t.getId(), "MANUAL", 1L);

        assertThatThrownBy(() -> runService.trigger(t.getId(), "MANUAL", 1L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.STATE_CONFLICT);
    }

    @Test
    @DisplayName("并发触发同一 Task，只有一个成功")
    void concurrentTriggerOnlyOneWins() throws Exception {
        CollectionTask t = newTask("IDLE");
        int threads = 6;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        List<Thread> list = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            Thread th = new Thread(() -> {
                try {
                    start.await();
                    runService.trigger(t.getId(), "MANUAL", 1L);
                    ok.incrementAndGet();
                } catch (Exception ignored) {
                    // 预期：只有一方成功
                }
            });
            th.start();
            list.add(th);
        }
        start.countDown();
        for (Thread th : list) {
            th.join(10_000);
        }
        assertThat(ok.get()).as("并发触发只能有一个成功").isEqualTo(1);
    }

    @Test
    @DisplayName("PAUSED / DISABLED 任务拒绝触发")
    void pausedAndDisabledRejected() {
        CollectionTask paused = newTask("PAUSED");
        CollectionTask disabled = newTask("DISABLED");

        assertThatThrownBy(() -> runService.trigger(paused.getId(), "MANUAL", 1L))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> runService.trigger(disabled.getId(), "MANUAL", 1L))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("Run 失败后 Task 回 IDLE，不复制 FAILED")
    void finishFailureKeepsTaskIdle() {
        CollectionTask t = newTask("IDLE");
        ColRun run = runService.trigger(t.getId(), "MANUAL", 1L);

        runService.finish(run.getId(), "FAILED", 0, 2, "adapter error");

        assertThat(taskMapper.selectById(t.getId()).getStatus())
                .as("Task 是定义态，不能被 Run 结果污染")
                .isEqualTo("IDLE");
        assertThat(runService.latestOf(t.getId()).getStatus()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("PAUSED 任务的 Run 结束后回到 PAUSED 而非 IDLE")
    void pausedTaskReturnsToPaused() {
        CollectionTask t = newTask("IDLE");
        ColRun run = runService.trigger(t.getId(), "MANUAL", 1L);

        // 模拟执行期间用户暂停
        taskMapper.releaseToIdle(t.getId(), "PAUSED");
        runService.finish(run.getId(), "COMPLETED", 1, 0, null);

        assertThat(taskMapper.selectById(t.getId()).getStatus()).isEqualTo("PAUSED");
    }

    @Test
    @DisplayName("不同任务可各自创建 Run，run_no 独立从 1 开始")
    void differentTasksIndependent() {
        CollectionTask a = newTask("IDLE");
        CollectionTask b = newTask("IDLE");

        assertThat(runService.trigger(a.getId(), "MANUAL", 1L).getRunNo()).isEqualTo(1);
        assertThat(runService.trigger(b.getId(), "MANUAL", 1L).getRunNo()).isEqualTo(1);
    }

    @Test
    @DisplayName("已结束的 Run 不能再次结束")
    void cannotFinishTwice() {
        CollectionTask t = newTask("IDLE");
        ColRun run = runService.trigger(t.getId(), "MANUAL", 1L);
        runService.finish(run.getId(), "COMPLETED", 1, 0, null);

        assertThatThrownBy(() -> runService.finish(run.getId(), "FAILED", 0, 1, "x"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.STATE_CONFLICT);
    }
}
