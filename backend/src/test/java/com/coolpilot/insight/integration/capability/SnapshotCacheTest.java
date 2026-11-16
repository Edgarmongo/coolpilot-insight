package com.coolpilot.insight.integration.capability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 快照原子缓存测试（SOURCE-04-02 §三 / 验收 A06、A08）。
 *
 * <p>核心命题：**缓存可以保留诊断上下文，但不能让过期数据继续获得执行资格。**
 *
 * <p>用 {@link SnapshotProvider} 的 lambda 实现精确控制成功/失败，
 * 不需要起 HTTP 服务，也不需要 mock 框架。
 */
class SnapshotCacheTest {

    private static RegistrySnapshot snapshotWithId(String id) {
        return new RegistrySnapshot(id, "1.1", "1.1", "2026-11-16T12:00:00Z",
                List.of(new RegistrySnapshot.AdapterView(
                        "X", "DOUYIN", "1.0.0", "LOADED", 1)),
                List.of(),
                List.of());
    }

    private static CollectionServiceClient.FetchResult ok(String id) {
        return new CollectionServiceClient.FetchResult(
                snapshotWithId(id), CollectionServiceClient.FetchResult.Kind.OK, null);
    }

    private static CollectionServiceClient.FetchResult fail() {
        return CollectionServiceClient.FetchResult.failure(
                CollectionServiceClient.FetchResult.Kind.UNREACHABLE, "连接超时");
    }

    // ------------------------------------------------------------------
    // 状态机
    // ------------------------------------------------------------------
    @Test
    @DisplayName("首次获取成功 → 保存并可用")
    void firstSuccessStoresSnapshot() {
        var cache = new SnapshotCache(() -> ok("sha256:a"), 60);
        var read = cache.get();

        assertThat(read.snapshot()).isNotNull();
        assertThat(read.snapshot().snapshotId()).isEqualTo("sha256:a");
        assertThat(read.fresh()).isTrue();
        assertThat(read.usableForExecution()).isTrue();
    }

    @Test
    @DisplayName("成功且 ID 相同 → 内容不变，仍然可用")
    void sameIdKeepsSnapshot() {
        var calls = new AtomicInteger();
        var cache = new SnapshotCache(() -> {
            calls.incrementAndGet();
            return ok("sha256:a");
        }, 60);

        cache.get();
        // 第二次：fresh 时不会重新请求
        var second = cache.get();
        assertThat(second.snapshot().snapshotId()).isEqualTo("sha256:a");
        assertThat(second.usableForExecution()).isTrue();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("成功且 ID 不同 → 原子替换整个快照")
    void differentIdReplacesSnapshot() {
        var seq = new AtomicInteger();
        var cache = new SnapshotCache(
                () -> ok(seq.getAndIncrement() == 0 ? "sha256:a" : "sha256:b"), 0);
        // ttl=0 会被规范化成 60，这里用 reflect 不方便；改用 refresh 直接触发
        cache.refresh();
        var replaced = cache.refresh();

        assertThat(replaced.snapshot().snapshotId()).isEqualTo("sha256:b");
        assertThat(replaced.usableForExecution()).isTrue();
    }

    @Test
    @DisplayName("失败且存在历史 → 保留快照但标记 STALE，且不可用于执行")
    void failureKeepsHistoryButBlocksExecution() {
        var first = new AtomicInteger();
        var cache = new SnapshotCache(
                () -> first.getAndIncrement() == 0 ? ok("sha256:a") : fail(), 60);

        cache.refresh();               // 成功
        var afterFailure = cache.refresh();  // 失败

        assertThat(afterFailure.snapshot())
                .as("失败时不能丢弃历史快照——那是排查上下文")
                .isNotNull();
        assertThat(afterFailure.snapshot().snapshotId()).isEqualTo("sha256:a");
        assertThat(afterFailure.fresh()).isFalse();
        assertThat(afterFailure.usableForExecution())
                .as("STALE 快照绝不能作为执行准入依据")
                .isFalse();
        assertThat(afterFailure.detail()).contains("刷新失败");
    }

    @Test
    @DisplayName("失败且无历史 → 无可用快照")
    void failureWithoutHistoryYieldsNothing() {
        var cache = new SnapshotCache(SnapshotCacheTest::fail, 60);
        var read = cache.get();

        assertThat(read.snapshot()).isNull();
        assertThat(read.fresh()).isFalse();
        assertThat(read.usableForExecution()).isFalse();
    }

    @Test
    @DisplayName("服务恢复 → 清除 STALE，恢复可用")
    void recoveryClearsStale() {
        var healthy = new java.util.concurrent.atomic.AtomicBoolean(true);
        var cache = new SnapshotCache(
                () -> healthy.get() ? ok("sha256:a") : fail(), 60);

        cache.refresh();
        healthy.set(false);
        assertThat(cache.refresh().usableForExecution()).isFalse();

        healthy.set(true);
        var recovered = cache.refresh();
        assertThat(recovered.fresh()).isTrue();
        assertThat(recovered.usableForExecution()).isTrue();
    }

    @Test
    @DisplayName("失败后短期内不反复重试（避免打爆不可用的服务）")
    void doesNotRetryImmediately() {
        var calls = new AtomicInteger();
        var cache = new SnapshotCache(() -> {
            calls.incrementAndGet();
            return fail();
        }, 60);

        cache.get();
        int afterFirst = calls.get();
        cache.get();
        assertThat(calls.get())
                .as("短时间内不应重复请求")
                .isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("TTL 过期后不得作为执行准入依据")
    void expiredTtlBlocksExecution() throws InterruptedException {
        var cache = new SnapshotCache(() -> ok("sha256:a"), 1);
        cache.refresh();
        assertThat(cache.read().usableForExecution()).isTrue();

        Thread.sleep(1200);
        var stale = cache.read();
        assertThat(stale.fresh()).isFalse();
        assertThat(stale.usableForExecution())
                .as("超过 TTL 的快照不得用于执行准入")
                .isFalse();
    }

    @Test
    @DisplayName("非法快照（解析失败）不得覆盖已有的合法快照")
    void invalidPayloadDoesNotOverwriteValidSnapshot() {
        var first = new AtomicInteger();
        var cache = new SnapshotCache(() -> {
            if (first.getAndIncrement() == 0) {
                return ok("sha256:a");
            }
            return CollectionServiceClient.FetchResult.failure(
                    CollectionServiceClient.FetchResult.Kind.UNPARSEABLE, "缺少 capabilities 数组");
        }, 60);

        cache.refresh();
        var afterInvalid = cache.refresh();

        assertThat(afterInvalid.snapshot().snapshotId())
                .as("结构不合法的响应不能覆盖已有合法快照")
                .isEqualTo("sha256:a");
        assertThat(afterInvalid.usableForExecution()).isFalse();
    }
}
