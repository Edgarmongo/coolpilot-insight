package com.coolskyai.coolpilot.insight;

import com.coolskyai.coolpilot.insight.entity.CollectionTask;
import com.coolskyai.coolpilot.insight.entity.CollectionTaskItem;
import com.coolskyai.coolpilot.insight.entity.Video;
import com.coolskyai.coolpilot.insight.mapper.CollectionTaskItemMapper;
import com.coolskyai.coolpilot.insight.mapper.CollectionTaskMapper;
import com.coolskyai.coolpilot.insight.mapper.VideoMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 采集调度的两个隐式契约。
 *
 * <p>这两个点在从 MyBatis-Plus 迁到原生 MyBatis 时最容易踩坏：
 * 一是「条件更新」实现的原子领取，二是「终态释放 active_flag 必须写 NULL」。
 * 后者如果沿用 updateById，会因为跳过 null 而静默失效，导致该视频永久无法再次采集。
 */
class CollectionTaskSchedulingTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(1);

    @Autowired
    private CollectionTaskMapper taskMapper;

    @Autowired
    private CollectionTaskItemMapper itemMapper;

    @Autowired
    private VideoMapper videoMapper;

    private CollectionTask newTask(String status) {
        CollectionTask t = new CollectionTask();
        t.setTaskNo("T" + System.nanoTime() + "-" + SEQ.getAndIncrement());
        t.setTaskType("COLLECTION");
        t.setStatus(status);
        t.setOwnerUserId(1L);          // V6 种子管理员
        t.setTotalItems(0);
        t.setSuccessItems(0);
        t.setFailedItems(0);
        return t;
    }

    @Test
    @DisplayName("claimPending 只有在 PENDING 时才成功，第二次返回 0（互斥领取）")
    void claimPendingIsAtomic() {
        CollectionTask t = newTask("PENDING");
        taskMapper.insert(t);

        int first = taskMapper.claimPending(t.getId(), LocalDateTime.now());
        int second = taskMapper.claimPending(t.getId(), LocalDateTime.now());

        assertThat(first).as("首次领取必须成功").isEqualTo(1);
        assertThat(second).as("已被领取的任务不能被再次领取").isZero();
        assertThat(taskMapper.selectById(t.getId()).getStatus()).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("非 PENDING 任务无法被领取")
    void claimPendingRejectsNonPending() {
        CollectionTask t = newTask("RUNNING");
        taskMapper.insert(t);

        assertThat(taskMapper.claimPending(t.getId(), LocalDateTime.now())).isZero();
    }

    @Test
    @DisplayName("clearActiveFlag 必须真正写入 NULL（updateById 会跳过 null）")
    void clearActiveFlagWritesNull() {
        Video v = new Video();
        v.setAwemeId("sched-" + SEQ.getAndIncrement());
        v.setTitle("调度测试视频");
        v.setStatus(1);
        v.setDiggCount(0L);
        videoMapper.insert(v);

        CollectionTask t = newTask("RUNNING");
        taskMapper.insert(t);

        CollectionTaskItem item = new CollectionTaskItem();
        item.setTaskId(t.getId());
        item.setVideoId(v.getId());
        item.setStatus("PENDING");
        item.setRetryCount(0);
        item.setMaxRetry(3);
        item.setRequestedMax(300);
        item.setCollectedCount(0L);
        item.setActiveFlag(1);
        itemMapper.insert(item);

        assertThat(itemMapper.selectById(item.getId()).getActiveFlag()).isEqualTo(1);

        int affected = itemMapper.clearActiveFlag(item.getId());

        assertThat(affected).isEqualTo(1);
        assertThat(itemMapper.selectById(item.getId()).getActiveFlag())
                .as("终态释放后 active_flag 必须为 NULL，否则该视频永久无法再次采集")
                .isNull();
    }
}
