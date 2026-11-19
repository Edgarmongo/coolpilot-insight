package com.coolpilot.insight.collection;

import com.coolpilot.insight.collection.application.AdmissionGatedCollectionClient;
import com.coolpilot.insight.collection.application.CollectionAdmissionGuard;
import com.coolpilot.insight.common.exception.BizException;
import com.coolpilot.insight.integration.collection.CollectionClient;
import com.coolpilot.insight.integration.common.CommandResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 采集能力闸门（SOURCE-04-FOLLOWUP-FIX-02 · P1）。
 *
 * <p>要证明的只有一件事：**准入不通过时，委托对象一次都不会被调用**。
 * 委托对象就是 HTTP 客户端 / 子进程实现，所以"零调用"等价于
 * "不起子进程、不发平台请求"。
 */
class AdmissionGatedCollectionClientTest {

    /** 记录调用次数的假实现——它就是"会不会真的去执行"的替身。 */
    private static final class CountingDelegate implements CollectionClient {
        final AtomicInteger collectCalls = new AtomicInteger();
        final AtomicInteger searchCalls = new AtomicInteger();

        @Override
        public CommandResult collectComments(String externalContentId, int maxComments,
                                             boolean useLogin, Long taskId, Long itemId) {
            collectCalls.incrementAndGet();
            return new CommandResult(0, "ok", 1);
        }

        @Override
        public CommandResult searchByKeyword(String keyword, int max) {
            searchCalls.incrementAndGet();
            return new CommandResult(0, "ok", 1);
        }
    }

    /** 放行用的守卫。 */
    private static final class AllowAllGuard extends CollectionAdmissionGuard {
        AllowAllGuard() {
            super(null);
        }

        @Override
        public void requireExecutable(String sourceCode, String capabilityCode) {
            // 放行
        }
    }

    /** 拒绝用的守卫，消息里带上 status/reason。 */
    private static final class DenyGuard extends CollectionAdmissionGuard {
        private final String capability;

        DenyGuard(String capability) {
            super(null);
            this.capability = capability;
        }

        @Override
        public void requireExecutable(String sourceCode, String capabilityCode) {
            if (capability.equals(capabilityCode)) {
                throw new BizException(com.coolpilot.insight.common.exception.ErrorCode.STATE_CONFLICT,
                        "当前不允许执行 " + capabilityCode
                                + "：UNREGISTERED/VERIFICATION_REQUIRED —— 没有已接受的验证证据");
            }
        }
    }

    @Test
    @DisplayName("KEYWORD_SEARCH 未通过准入 → 不调用委托（不起子进程）")
    void searchIsBlockedBeforeDelegate() {
        CountingDelegate delegate = new CountingDelegate();
        AdmissionGatedCollectionClient client =
                new AdmissionGatedCollectionClient(delegate, new DenyGuard("KEYWORD_SEARCH"));

        assertThatThrownBy(() -> client.searchByKeyword("足球", 10))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getMessage())
                        .contains("KEYWORD_SEARCH").contains("VERIFICATION_REQUIRED"));

        assertThat(delegate.searchCalls.get())
                .as("准入拒绝后不得启动子进程")
                .isZero();
    }

    @Test
    @DisplayName("COMMENT 未通过准入 → 不调用委托（不发 HTTP 作业）")
    void collectIsBlockedBeforeDelegate() {
        CountingDelegate delegate = new CountingDelegate();
        AdmissionGatedCollectionClient client =
                new AdmissionGatedCollectionClient(delegate, new DenyGuard("COMMENT"));

        assertThatThrownBy(() -> client.collectComments("7703721523349239951", 20, false, 1L, 2L))
                .isInstanceOf(BizException.class);

        assertThat(delegate.collectCalls.get()).isZero();
    }

    @Test
    @DisplayName("准入通过 → 正常委托，且只调用一次")
    void passesThroughWhenAllowed() {
        CountingDelegate delegate = new CountingDelegate();
        AdmissionGatedCollectionClient client =
                new AdmissionGatedCollectionClient(delegate, new AllowAllGuard());

        assertThatCode(() -> client.collectComments("123", 10, false, 1L, 1L))
                .doesNotThrowAnyException();
        assertThatCode(() -> client.searchByKeyword("足球", 5)).doesNotThrowAnyException();

        assertThat(delegate.collectCalls.get()).isEqualTo(1);
        assertThat(delegate.searchCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("拒一个能力不影响另一个：搜被拒时采集仍可放行")
    void denialIsPerCapability() {
        CountingDelegate delegate = new CountingDelegate();
        AdmissionGatedCollectionClient client =
                new AdmissionGatedCollectionClient(delegate, new DenyGuard("KEYWORD_SEARCH"));

        client.collectComments("123", 10, false, 1L, 1L);

        assertThat(delegate.collectCalls.get()).isEqualTo(1);
        assertThat(delegate.searchCalls.get()).isZero();
    }
}
