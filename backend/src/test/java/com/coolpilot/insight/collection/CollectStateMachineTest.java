package com.coolpilot.insight.collection;

import com.coolpilot.insight.AbstractIntegrationTest;
import com.coolpilot.insight.collection.application.CollectService;
import com.coolpilot.insight.collection.domain.CollectionTask;
import com.coolpilot.insight.collection.infrastructure.persistence.CollectionTaskMapper;
import com.coolpilot.insight.common.exception.BizException;
import com.coolpilot.insight.common.exception.ErrorCode;
import com.coolpilot.insight.common.security.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 采集任务状态机（拆 CollectServiceImpl 之前的护栏）。
 *
 * <p>这个类要拆成「状态机 / 调度 / 落盘」三块，拆之前必须先把状态流转的
 * 允许与拒绝固定下来，否则拆完无法证明行为没变。
 *
 * <p>只测不依赖外部（Chrome/Python）的部分：cancel 与 retry 的状态校验。
 */
class CollectStateMachineTest extends AbstractIntegrationTest {

    @Autowired
    private CollectService collectService;

    @Autowired
    private CollectionTaskMapper taskMapper;

    @AfterEach
    void clear() {
        RequestContext.clear();
    }

    private void loginAdmin() {
        RequestContext.setUserId(1L);
        RequestContext.setRole(1);
    }

    private CollectionTask seedTask(String status) {
        CollectionTask t = new CollectionTask();
        t.setTaskNo("SM" + System.nanoTime());
        t.setTaskType("COLLECTION");
        t.setStatus(status);
        t.setOwnerUserId(1L);
        t.setTotalItems(1);
        t.setSuccessItems(0);
        t.setFailedItems(0);
        taskMapper.insert(t);
        return t;
    }

    @Test
    @DisplayName("取消不存在的任务返回 404 业务码")
    void cancelMissingTask() {
        loginAdmin();
        assertThatThrownBy(() -> collectService.cancel(999_999_999L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("已结束的任务不能再取消")
    void cannotCancelFinishedTask() {
        loginAdmin();
        CollectionTask t = seedTask("CANCELLED");

        assertThatThrownBy(() -> collectService.cancel(t.getId()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.STATE_CONFLICT);
    }

    @Test
    @DisplayName("只能重试失败/部分成功的任务")
    void onlyFailedCanRetry() {
        loginAdmin();
        CollectionTask success = seedTask("SUCCESS");

        assertThatThrownBy(() -> collectService.retry(success.getId()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.STATE_CONFLICT);
    }

    @Test
    @DisplayName("取消进行中的任务后状态变为 CANCELLED")
    void cancelPendingChangesStatus() {
        loginAdmin();
        CollectionTask t = seedTask("PENDING");

        collectService.cancel(t.getId());

        assertThat(taskMapper.selectById(t.getId()).getStatus()).isEqualTo("CANCELLED");
    }
}
