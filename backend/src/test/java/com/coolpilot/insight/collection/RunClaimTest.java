package com.coolpilot.insight.collection;

import com.coolpilot.insight.AbstractIntegrationTest;
import com.coolpilot.insight.collection.domain.ColRun;
import com.coolpilot.insight.collection.domain.CollectionTask;
import com.coolpilot.insight.collection.infrastructure.persistence.ColRunMapper;
import com.coolpilot.insight.collection.infrastructure.persistence.CollectionTaskMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Run 级原子领取（C-03）。
 *
 * <p>只验证数据库条件更新的互斥性，不触发真实采集——
 * 依赖外部进程的测试会时绿时红，比没有更糟。
 */
class RunClaimTest extends AbstractIntegrationTest {

    @Autowired private ColRunMapper runMapper;
    @Autowired private CollectionTaskMapper taskMapper;

    private ColRun seedPendingRun() {
        CollectionTask t = new CollectionTask();
        t.setTaskNo("CLAIM" + System.nanoTime());
        t.setTaskType("COLLECTION");
        t.setStatus("RUNNING");
        t.setOwnerUserId(1L);
        t.setTotalItems(0);
        t.setSuccessItems(0);
        t.setFailedItems(0);
        taskMapper.insert(t);

        ColRun r = new ColRun();
        r.setTaskId(t.getId());
        r.setRunNo(1);
        r.setTriggerType("MANUAL");
        r.setStatus("PENDING");
        runMapper.insert(r);
        return r;
    }

    @Test
    @DisplayName("同一 Run 只能被领取一次")
    void claimOnceOnly() {
        ColRun r = seedPendingRun();

        assertThat(runMapper.claimPending(r.getId())).isEqualTo(1);
        assertThat(runMapper.claimPending(r.getId()))
                .as("第二次领取应失败，避免多 Worker 重复执行")
                .isEqualTo(0);
        assertThat(runMapper.selectById(r.getId()).getStatus()).isEqualTo("RUNNING");
        assertThat(runMapper.selectById(r.getId()).getStartedAt()).isNotNull();
    }

    @Test
    @DisplayName("selectPendingOldest 只返回 PENDING，不返回已领取的")
    void picksOnlyPending() {
        ColRun r = seedPendingRun();
        runMapper.claimPending(r.getId());

        ColRun next = runMapper.selectPendingOldest();
        assertThat(next == null || !next.getId().equals(r.getId())).isTrue();
    }

    @Test
    @DisplayName("活动 Run 计数把 PENDING/RUNNING/PAUSED 都算在内")
    void activeCountIncludesAll() {
        ColRun r = seedPendingRun();
        assertThat(runMapper.countActiveByTask(r.getTaskId())).isEqualTo(1);
        runMapper.claimPending(r.getId());
        assertThat(runMapper.countActiveByTask(r.getTaskId())).isEqualTo(1);

        ColRun done = new ColRun();
        done.setId(r.getId());
        done.setStatus("COMPLETED");
        done.setSuccessCount(0);
        done.setFailedCount(0);
        done.setTotalExecutionCount(0);
        runMapper.updateResult(done);
        assertThat(runMapper.countActiveByTask(r.getTaskId())).isZero();
    }
}
