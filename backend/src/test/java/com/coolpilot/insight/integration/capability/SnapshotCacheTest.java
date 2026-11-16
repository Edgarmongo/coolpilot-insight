package com.coolpilot.insight.integration.capability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 快照原子缓存测试（SOURCE-04-02 §三 / 验收 A06、A08）。
 *
 * <p>核心命题：缓存可以保留诊断上下文，但**不能让过期数据继续获得执行资格**。
 *
 * <p>并发部分验证的是 Single-flight 的六条不变量：
 * 同一次刷新只发一次请求、等待者共享结果、失败结果一致、
 * 退避生效、网络不占锁、读线程看到的永远是完整快照。
 */
class SnapshotCacheTest {

    private static RegistrySnapshot snapshotWithId(String id) {
        return new RegistrySnapshot(id, "1.0", "1.1", "2026-11-16T12:00:00Z",
                List.of(new RegistrySnapshot.AdapterView("X", "DOUYIN", "1.0.0", "LOADED",
                        "app.x", true, 1, null, null, List.of())),
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

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // 三态模型
    // ------------------------------------------------------------------
    @Test
    @DisplayName("初始状态为 UNAVAILABLE，且不可执行")
    void initialStateIsUnavailable() {
        var cache = new SnapshotCache(() -> ok("sha256:a"), 60);
        var read = cache.read();

        assertThat(read.state()).isEqualTo(SnapshotCache.SnapshotCacheState.UNAVAILABLE);
        assertThat(read.snapshot()).isNull();
        assertThat(read.usableForExecution()).isFalse();
    }

    @Test
    @DisplayName("首次获取成功 → FRESH，可执行")
    void firstSuccessIsFresh() {
        var cache = new SnapshotCache(() -> ok("sha256:a"), 60);
        var read = cache.get();

        assertThat(read.state()).isEqualTo(SnapshotCache.SnapshotCacheState.FRESH);
        assertThat(read.snapshot().snapshotId()).isEqualTo("sha256:a");
        assertThat(read.usableForExecution()).isTrue();
    }

    @Test
    @DisplayName("超过 TTL → STALE，保留快照但不可执行")
    void expiredTtlIsStaleButKeepsSnapshot() {
        var cache = new SnapshotCache(() -> ok("sha256:a"), 1);
        cache.refresh();
        assertThat(cache.read().state()).isEqualTo(SnapshotCache.SnapshotCacheState.FRESH);

        sleep(1200);
        var stale = cache.read();
        assertThat(stale.state()).isEqualTo(SnapshotCache.SnapshotCacheState.STALE);
        assertThat(stale.snapshot())
                .as("STALE 仍要保留快照——那是排查上下文")
                .isNotNull();
        assertThat(stale.usableForExecution())
                .as("超过 TTL 的快照不得用于执行准入")
                .isFalse();
    }

    @Test
    @DisplayName("刷新失败且无历史 → UNAVAILABLE")
    void failureWithoutHistoryIsUnavailable() {
        var cache = new SnapshotCache(SnapshotCacheTest::fail, 60);
        var read = cache.get();

        assertThat(read.state()).isEqualTo(SnapshotCache.SnapshotCacheState.UNAVAILABLE);
        assertThat(read.snapshot()).isNull();
        assertThat(read.usableForExecution()).isFalse();
    }

    @Test
    @DisplayName("状态与可用性不变量：非 FRESH 不得标记可执行")
    void nonFreshCannotBeExecutable() {
        assertThatThrownBy(() -> new SnapshotCache.SnapshotRead(
                snapshotWithId("sha256:a"), SnapshotCache.SnapshotCacheState.STALE,
                true, "", false, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new SnapshotCache.SnapshotRead(
                null, SnapshotCache.SnapshotCacheState.FRESH,
                true, "", false, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------
    // 基本状态机
    // ------------------------------------------------------------------
    @Test
    @DisplayName("成功且 ID 相同 → 内容不变，仍可用，且不重复请求")
    void sameIdKeepsSnapshot() {
        var calls = new AtomicInteger();
        var cache = new SnapshotCache(() -> {
            calls.incrementAndGet();
            return ok("sha256:a");
        }, 60);

        cache.get();
        var second = cache.get();
        assertThat(second.snapshot().snapshotId()).isEqualTo("sha256:a");
        assertThat(second.usableForExecution()).isTrue();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("成功且 ID 不同 → 整体替换")
    void differentIdReplacesSnapshot() {
        var seq = new AtomicInteger();
        var cache = new SnapshotCache(
                () -> ok(seq.getAndIncrement() == 0 ? "sha256:a" : "sha256:b"), 60);

        cache.refresh();
        var replaced = cache.refresh();

        assertThat(replaced.snapshot().snapshotId()).isEqualTo("sha256:b");
        assertThat(replaced.usableForExecution()).isTrue();
    }

    @Test
    @DisplayName("服务恢复 → 清除 STALE（退避期过后）")
    void recoveryClearsStale() {
        var healthy = new java.util.concurrent.atomic.AtomicBoolean(true);
        // 退避设为 0：本用例验证的是"恢复后能回到 FRESH"，
        // 退避本身的拦截语义由 doesNotRetryWithinBackoff 覆盖。
        // 注意：refresh() 同样受退避约束 —— 这是有意的，
        // 否则"主动刷新"会成为绕过退避的后门。
        var cache = new SnapshotCache(
                () -> healthy.get() ? ok("sha256:a") : fail(), 60, 0);

        cache.refresh();
        healthy.set(false);
        var failed = cache.refresh();
        assertThat(failed.usableForExecution()).isFalse();

        healthy.set(true);
        var recovered = cache.refresh();
        assertThat(recovered.state()).isEqualTo(SnapshotCache.SnapshotCacheState.FRESH);
        assertThat(recovered.usableForExecution()).isTrue();
    }

    @Test
    @DisplayName("主动 refresh() 同样受退避约束（不是绕过后门）")
    void manualRefreshIsAlsoThrottled() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var cache = new SnapshotCache(() -> {
            calls.incrementAndGet();
            return fail();
        }, 60, 5);

        cache.refresh();
        int afterFirst = calls.get();
        cache.refresh();
        cache.refresh();
        assertThat(calls.get())
                .as("退避期内主动 refresh 也不应重复请求")
                .isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("非法快照不得覆盖已有的合法快照")
    void invalidPayloadDoesNotOverwriteValidSnapshot() {
        var first = new AtomicInteger();
        var cache = new SnapshotCache(() -> {
            if (first.getAndIncrement() == 0) {
                return ok("sha256:a");
            }
            return CollectionServiceClient.FetchResult.failure(
                    CollectionServiceClient.FetchResult.Kind.MALFORMED_RESPONSE,
                    "缺少 capabilities 数组");
        }, 60);

        cache.refresh();
        var afterInvalid = cache.refresh();

        assertThat(afterInvalid.snapshot().snapshotId())
                .as("结构不合法的响应不能覆盖已有合法快照")
                .isEqualTo("sha256:a");
        assertThat(afterInvalid.usableForExecution()).isFalse();
    }

    // ------------------------------------------------------------------
    // 退避
    // ------------------------------------------------------------------
    @Test
    @DisplayName("失败后在退避期内不重复请求")
    void doesNotRetryWithinBackoff() {
        var calls = new AtomicInteger();
        var cache = new SnapshotCache(() -> {
            calls.incrementAndGet();
            return fail();
        }, 60, 5);

        cache.get();
        int afterFirst = calls.get();
        cache.get();
        assertThat(calls.get()).as("退避期内不应重复请求").isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("退避结束后允许重新刷新")
    void allowsRetryAfterBackoff() {
        var calls = new AtomicInteger();
        var cache = new SnapshotCache(() -> {
            calls.incrementAndGet();
            return fail();
        }, 60, 0);   // 退避 0 秒：用于验证"结束即允许"

        cache.get();
        int afterFirst = calls.get();
        cache.get();
        assertThat(calls.get()).as("退避已结束，应允许再试一次").isGreaterThan(afterFirst);
    }

    // ------------------------------------------------------------------
    // Single-flight 并发不变量
    // ------------------------------------------------------------------
    @Test
    @DisplayName("并发首次获取：20 线程只触发 1 次 HTTP")
    void concurrentFirstFetchIssuesSingleHttpCall() throws Exception {
        var calls = new AtomicInteger();
        var cache = new SnapshotCache(() -> {
            calls.incrementAndGet();
            sleep(80);          // 拉长窗口，确保并发真的重叠
            return ok("sha256:a");
        }, 60);

        var results = runConcurrently(cache, 20);

        assertThat(calls.get())
                .as("Single-flight：20 个并发只应触发 1 次 HTTP")
                .isEqualTo(1);
        assertThat(results).hasSize(20);
        assertThat(results)
                .extracting(r -> r.snapshot().snapshotId())
                .containsOnly("sha256:a");
        assertThat(results).allMatch(SnapshotCache.SnapshotRead::usableForExecution);
    }

    @Test
    @DisplayName("并发刷新过期缓存：20 线程只触发 1 次 HTTP")
    void concurrentRefreshOfStaleCacheIssuesSingleHttpCall() throws Exception {
        var calls = new AtomicInteger();
        var cache = new SnapshotCache(() -> {
            calls.incrementAndGet();
            sleep(80);
            return ok("sha256:a");
        }, 1);

        cache.refresh();            // 预热
        sleep(1200);                // 超 TTL → STALE
        assertThat(cache.read().state()).isEqualTo(SnapshotCache.SnapshotCacheState.STALE);
        int before = calls.get();

        var results = runConcurrently(cache, 20);

        assertThat(calls.get() - before)
                .as("并发刷新过期缓存只应触发 1 次 HTTP")
                .isEqualTo(1);
        assertThat(results).allMatch(SnapshotCache.SnapshotRead::usableForExecution);
        assertThat(results)
                .extracting(r -> r.snapshot().snapshotId())
                .containsOnly("sha256:a");
    }

    @Test
    @DisplayName("并发刷新失败：所有线程得到一致结果")
    void concurrentFailureYieldsConsistentResult() throws Exception {
        var calls = new AtomicInteger();
        var cache = new SnapshotCache(() -> {
            calls.incrementAndGet();
            sleep(60);
            return fail();
        }, 60);

        var results = runConcurrently(cache, 20);

        assertThat(calls.get())
                .as("失败也只应触发 1 次 HTTP，不能让 20 个线程各试一次")
                .isEqualTo(1);
        assertThat(results)
                .extracting(SnapshotCache.SnapshotRead::state)
                .containsOnly(SnapshotCache.SnapshotCacheState.UNAVAILABLE);
        assertThat(results).noneMatch(SnapshotCache.SnapshotRead::usableForExecution);
    }

    @Test
    @DisplayName("刷新期间读取历史快照：看到的是完整旧快照，不是部分更新")
    void readDuringRefreshSeesCompleteOldSnapshot() throws Exception {
        var gate = new CountDownLatch(1);
        var refreshStarted = new CountDownLatch(1);
        var seq = new AtomicInteger();

        var cache = new SnapshotCache(() -> {
            if (seq.getAndIncrement() == 0) {
                return ok("sha256:old");
            }
            refreshStarted.countDown();
            try {
                gate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ok("sha256:new");
        }, 1);

        cache.refresh();
        assertThat(cache.read().snapshot().snapshotId()).isEqualTo("sha256:old");
        sleep(1200);   // 超 TTL，制造"需要刷新"的场景

        var refresher = new Thread(cache::refresh);
        refresher.start();
        assertThat(refreshStarted.await(5, TimeUnit.SECONDS)).isTrue();

        // 刷新进行中：读线程必须看到完整旧快照
        var during = cache.read();
        assertThat(during.snapshot().snapshotId())
                .as("刷新期间读到的应是完整旧快照，不能是新旧混合")
                .isEqualTo("sha256:old");
        assertThat(during.state()).isEqualTo(SnapshotCache.SnapshotCacheState.STALE);
        assertThat(during.usableForExecution()).isFalse();

        gate.countDown();
        refresher.join(5000);
        assertThat(cache.read().snapshot().snapshotId()).isEqualTo("sha256:new");
    }

    @Test
    @DisplayName("并发读取不会被刷新阻塞（网络请求不占全局锁）")
    void concurrentReadNotBlockedByRefresh() throws Exception {
        var gate = new CountDownLatch(1);
        var refreshStarted = new CountDownLatch(1);
        var seq = new AtomicInteger();

        var cache = new SnapshotCache(() -> {
            if (seq.getAndIncrement() == 0) {
                return ok("sha256:old");
            }
            refreshStarted.countDown();
            try {
                gate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ok("sha256:new");
        }, 1);

        cache.refresh();
        sleep(1200);

        var refresher = new Thread(cache::refresh);
        refresher.start();
        assertThat(refreshStarted.await(5, TimeUnit.SECONDS)).isTrue();

        // 刷新卡在网络上时，读操作应立即返回
        long start = System.nanoTime();
        var read = cache.read();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(read.snapshot()).isNotNull();
        assertThat(elapsedMs)
                .as("读操作不应被刷新中的网络请求阻塞")
                .isLessThan(500);

        gate.countDown();
        refresher.join(5000);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------
    private static List<SnapshotCache.SnapshotRead> runConcurrently(
            SnapshotCache cache, int threads) throws Exception {
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(threads);
        var results = new CopyOnWriteArrayList<SnapshotCache.SnapshotRead>();
        var errors = new ConcurrentLinkedQueue<Throwable>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        results.add(cache.get());
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(20, TimeUnit.SECONDS))
                    .as("并发任务必须在超时前全部完成")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(errors).as("并发过程中不应有异常: %s", errors).isEmpty();
        return results;
    }
}
