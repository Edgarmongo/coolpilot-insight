package com.coolpilot.insight.integration.capability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 统一快照的**原子缓存**，带 Single-flight 刷新协调（SOURCE-04-02 §三）。
 *
 * <p>缓存单位是**完整快照**，不是"每个端点一份"。这是快照一致性的落地前提：
 * 只要能分别缓存两个端点，就一定能拼出混合数据。
 *
 * <h3>为什么不能只靠 synchronized</h3>
 * <p>第一轮实现用 {@code synchronized refresh()}，评审确认它<b>不构成并发请求合并</b>：
 *
 * <pre>
 *  t1  T1: read() → 不新鲜
 *  t2  T2: read() → 不新鲜
 *  t3  T1: 进入 refresh()（持锁），发起 HTTP
 *  t4  T2: 阻塞在锁上
 *  t5  T2: 拿到锁 —— 不复查，再次发起 HTTP
 * </pre>
 *
 * <p>N 个并发 → N 次 HTTP。现在的做法是 Single-flight：
 * 同一时刻只有一个线程真正发起刷新，其余线程**共享同一次结果**。
 *
 * <h3>三态新鲜度</h3>
 * <p>{@link SnapshotCacheState} 是**缓存元数据**，不是 Effective 状态：
 * 它只回答"这份缓存现在能不能用"，不参与 Desired/Observed 核对。
 *
 * <p><b>铁律</b>：只有 {@code FRESH} 才允许作为执行准入依据。
 * {@code STALE} 可用于排障与展示历史信息，但<b>不得</b>被下游当成当前有效能力。
 */
@Component
public class SnapshotCache {

    private static final Logger log = LoggerFactory.getLogger(SnapshotCache.class);

    /** 默认刷新失败后的最小重试间隔（秒）。 */
    static final long DEFAULT_MIN_RETRY_SECONDS = 5;

    private final SnapshotProvider provider;
    private final long ttlSeconds;
    private final long minRetrySeconds;

    /** 当前缓存条目。**volatile + 整体替换**，不就地修改字段。 */
    private volatile Entry entry;

    /**
     * 最近一次刷新失败的时间。
     *
     * <p>单独存一份而不是放在 {@link Entry} 里，因为"失败且无历史"时
     * {@code entry} 为 {@code null}，但**仍然需要重试退避**——
     * 否则 Registry 不可用期间每次查询都会打一次网络请求。
     */
    private volatile Instant lastFailureAt;

    /** Single-flight 协调：只保护 {@link #inFlight} 的读写，**不包住网络请求**。 */
    private final Object flightLock = new Object();
    private CompletableFuture<SnapshotRead> inFlight;

    @Autowired
    public SnapshotCache(
            SnapshotProvider provider,
            @Value("${app.capability.observed-ttl-seconds:60}") long ttlSeconds) {
        this(provider, ttlSeconds, DEFAULT_MIN_RETRY_SECONDS);
    }

    /** 测试用：可缩短退避间隔，避免为了验证"退避结束"而等待 5 秒。 */
    SnapshotCache(SnapshotProvider provider, long ttlSeconds, long minRetrySeconds) {
        this.provider = provider;
        this.ttlSeconds = ttlSeconds > 0 ? ttlSeconds : 60;
        this.minRetrySeconds = minRetrySeconds >= 0 ? minRetrySeconds : DEFAULT_MIN_RETRY_SECONDS;
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------
    /**
     * 取快照。不新鲜时触发一次 **Single-flight** 刷新。
     *
     * <p>返回对象的 {@code usableForExecution} 是**唯一**可用于执行准入的判据，
     * 且它由 {@link SnapshotCacheState#FRESH} 派生，不单独维护。
     */
    public SnapshotRead get() {
        SnapshotRead current = read();
        if (current.state() == SnapshotCacheState.FRESH) {
            return current;
        }
        if (current.lastAttemptTooRecent()) {
            return current;
        }
        return refreshShared();
    }

    /** 只读当前状态，<b>不触发</b>网络请求。 */
    public SnapshotRead read() {
        Entry e = this.entry;
        boolean tooRecent = withinBackoff();

        if (e == null) {
            return new SnapshotRead(null, SnapshotCacheState.UNAVAILABLE, false,
                    "无可用快照", tooRecent, null);
        }
        Duration age = Duration.between(e.fetchedAt(), Instant.now());
        // 用 Duration 比较而不是 getSeconds()：后者会截断，
        // 1.2 秒在 TTL=1 时会算成 1 秒，导致"已过期"被判成"仍在 TTL 内"。
        boolean withinTtl = age.compareTo(Duration.ofSeconds(ttlSeconds)) <= 0;

        if (e.stale()) {
            String reason = e.failure() == null ? "未知失败" : e.failure().detail();
            return new SnapshotRead(e.snapshot(), SnapshotCacheState.STALE, false,
                    "快照已过期（最近一次刷新失败：" + reason + "）",
                    tooRecent, e.failure());
        }
        if (!withinTtl) {
            return new SnapshotRead(e.snapshot(), SnapshotCacheState.STALE, false,
                    "快照超过 TTL（" + ttlSeconds + "s）未刷新", tooRecent, e.failure());
        }
        return new SnapshotRead(e.snapshot(), SnapshotCacheState.FRESH, true,
                "快照新鲜", tooRecent, null);
    }

    // ------------------------------------------------------------------
    // Single-flight 刷新
    // ------------------------------------------------------------------
    /**
     * 主动刷新一次。
     *
     * <p>语义保证：
     * <ol>
     *   <li>同一时刻**最多一个**线程真正发起 HTTP 刷新；</li>
     *   <li>其余线程等待并**共享同一次结果**；</li>
     *   <li>成功后所有线程看到**同一个完整快照**；</li>
     *   <li>失败后所有线程得到**同一个** STALE/UNAVAILABLE 结果；</li>
     *   <li>网络请求**不占用** {@code flightLock}，读线程不会被阻塞；</li>
     *   <li>退避在真正发起 HTTP 前再次检查，防止并发排队绕过入口保护。</li>
     * </ol>
     */
    public SnapshotRead refresh() {
        return refreshShared();
    }

    private SnapshotRead refreshShared() {
        CompletableFuture<SnapshotRead> future;
        boolean owner;
        synchronized (flightLock) {
            if (inFlight == null) {
                inFlight = new CompletableFuture<>();
                future = inFlight;
                owner = true;
            } else {
                future = inFlight;
                owner = false;
            }
        }

        if (!owner) {
            // 等待者：共享 owner 的结果，不重复请求
            try {
                return future.join();
            } catch (CompletionException | CancellationException e) {
                // 共享刷新异常 → 回到当前状态，绝不伪造新鲜度
                return read();
            }
        }

        SnapshotRead result;
        try {
            result = performRefresh();
            future.complete(result);
            return result;
        } catch (RuntimeException | Error e) {
            // **必须完成 future**，否则已持有它的等待者会永久阻塞。
            // 清理 inFlight 只解决"后续请求能重新发起"，不解决"当前等待者醒来"。
            future.completeExceptionally(e);
            log.warn("[capability] 刷新过程异常（{}），等待线程共享失败: {}",
                    e.getClass().getSimpleName(), e.toString());
            if (e instanceof Error error) {
                // JVM 级错误（OOM / StackOverflow / NoClassDefFound…）不能吞掉：
                // 完成通知之后继续向上抛出，让容器与监控能看到真实的致命故障。
                throw error;
            }
            return read();
        } finally {
            synchronized (flightLock) {
                if (inFlight == future) {
                    inFlight = null;
                }
            }
        }
    }

    /**
     * 真正执行刷新。**调用者必须是 owner**，且本方法不在 {@code flightLock} 内运行。
     */
    private SnapshotRead performRefresh() {
        // 退避必须在真正发起 HTTP **之前**检查：
        // 多个线程可能都已通过 get() 入口检查后才排队，这里是最后一道闸门。
        if (withinBackoff()) {
            return read();
        }

        CollectionServiceClient.FetchResult result;
        try {
            result = provider.fetchSnapshot();
        } catch (RuntimeException e) {
            // Provider 抛异常也统一转成失败结果，保证等待者拿到一致结论
            result = CollectionServiceClient.FetchResult.failure(
                    CollectionServiceClient.FetchResult.Kind.UNREACHABLE, e.toString());
        }

        synchronized (flightLock) {
            applyResult(result);
        }
        return read();
    }

    /** 提交结果。只在 {@code flightLock} 内调用，且只做内存操作。 */
    private void applyResult(CollectionServiceClient.FetchResult result) {
        Instant now = Instant.now();
        if (result.ok()) {
            Entry prev = this.entry;
            if (prev != null && prev.snapshot().snapshotId()
                    .equals(result.snapshot().snapshotId())) {
                // 内容未变：只刷新获取时间与健康状态
                this.entry = new Entry(prev.snapshot(), now, false, null);
            } else {
                // 内容变了：整体替换，绝不做字段级合并
                this.entry = new Entry(result.snapshot(), now, false, null);
                log.info("[capability] Registry 快照已更新: {}",
                        shortId(result.snapshot().snapshotId()));
            }
            this.lastFailureAt = null;
            return;
        }

        this.lastFailureAt = now;
        SnapshotFailure failure = SnapshotFailure.of(result.kind(), result.detail());
        Entry prev = this.entry;
        if (prev != null) {
            this.entry = new Entry(prev.snapshot(), prev.fetchedAt(), true, failure);
            log.warn("[capability] 快照刷新失败（{}），保留历史快照并标记 STALE: {}",
                    result.kind(), result.detail());
        } else {
            this.entry = null;
            log.warn("[capability] 快照刷新失败（{}）且无历史可用: {}",
                    result.kind(), result.detail());
        }
    }

    private boolean withinBackoff() {
        Instant failureAt = this.lastFailureAt;
        return failureAt != null
                && Duration.between(failureAt, Instant.now())
                        .compareTo(Duration.ofSeconds(minRetrySeconds)) < 0;
    }

    /** 清空缓存（测试用）。 */
    public void clear() {
        synchronized (flightLock) {
            this.entry = null;
            this.lastFailureAt = null;
        }
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    long minRetrySeconds() {
        return minRetrySeconds;
    }

    private static String shortId(String id) {
        if (id == null) {
            return "null";
        }
        int colon = id.indexOf(':');
        String hex = colon >= 0 ? id.substring(colon + 1) : id;
        return hex.length() > 12 ? hex.substring(0, 12) + "…" : hex;
    }

    /** 缓存条目。字段全部 final，替换即整体替换。 */
    record Entry(RegistrySnapshot snapshot, Instant fetchedAt, boolean stale,
                 SnapshotFailure failure) {
    }

    /**
     * 缓存新鲜度。**这是缓存元数据，不是 Effective 状态枚举。**
     */
    public enum SnapshotCacheState {
        /** 当前获取成功，且未超过 TTL。**唯一**允许作为执行准入依据的状态。 */
        FRESH,
        /** 历史快照存在，但已过期或最近一次刷新失败。仅供排障与展示。 */
        STALE,
        /** 从未成功获取过快照，当前也无法获取。 */
        UNAVAILABLE
    }

    /**
     * 读取结果。
     *
     * @param snapshot            当前快照；{@link SnapshotCacheState#UNAVAILABLE} 时为 {@code null}
     * @param state               缓存新鲜度。**不要用 {@code snapshot != null} 判断可用性**
     * @param usableForExecution  唯一可用于执行准入的判据，由 {@code state == FRESH} 派生
     * @param detail              人类可读说明
     * @param lastAttemptTooRecent 上次失败距今过近，本次不应再重试
     * @param failure             最近一次失败的结构化描述；无失败为 {@code null}
     */
    public record SnapshotRead(RegistrySnapshot snapshot, SnapshotCacheState state,
                               boolean usableForExecution, String detail,
                               boolean lastAttemptTooRecent, SnapshotFailure failure) {

        /** 强制不变量：非 FRESH 不得标记可执行；UNAVAILABLE 不得携带快照。 */
        public SnapshotRead {
            if (state != SnapshotCacheState.FRESH && usableForExecution) {
                throw new IllegalArgumentException(
                        "非 FRESH 状态不得标记为可执行: " + state);
            }
            if (state == SnapshotCacheState.UNAVAILABLE && snapshot != null) {
                throw new IllegalArgumentException("UNAVAILABLE 不应携带快照");
            }
            if (state != SnapshotCacheState.UNAVAILABLE && snapshot == null) {
                throw new IllegalArgumentException(state + " 必须携带快照");
            }
        }

        /** 是否新鲜。等价于 {@code state == FRESH}。 */
        public boolean fresh() {
            return state == SnapshotCacheState.FRESH;
        }
    }
}
