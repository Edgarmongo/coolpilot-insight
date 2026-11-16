package com.coolpilot.insight.integration.capability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 统一快照的**原子缓存**（SOURCE-04-02 §三）。
 *
 * <p>缓存单位是**完整快照**，不是"每个端点一份"。这是快照一致性的落地前提：
 * 只要能分别缓存两个端点，就一定能拼出混合数据。
 *
 * <p>状态机：
 *
 * <table border="1">
 *   <tr><th>场景</th><th>动作</th></tr>
 *   <tr><td>首次获取成功</td><td>保存完整快照</td></tr>
 *   <tr><td>成功且 ID 相同</td><td>只刷新获取时间与健康状态</td></tr>
 *   <tr><td>成功且 ID 不同</td><td><b>原子替换</b>整个快照</td></tr>
 *   <tr><td>失败且存在历史</td><td>保留历史快照，标记 {@code STALE}</td></tr>
 *   <tr><td>失败且无历史</td><td>无可用快照</td></tr>
 *   <tr><td>结构/版本不合法</td><td>拒绝更新缓存，记录诊断</td></tr>
 * </table>
 *
 * <p><b>铁律</b>：{@code STALE} 快照可用于排障与展示历史信息，
 * <b>不得</b>被当作当前执行准入依据。Registry 不可达时，
 * 不能继续沿用上次的 {@code AVAILABLE} 作为准入依据。
 */
@Component
public class SnapshotCache {

    private static final Logger log = LoggerFactory.getLogger(SnapshotCache.class);

    private final SnapshotProvider client;
    private final long ttlSeconds;
    /** 刷新失败后的最小重试间隔，避免 Registry 长时间不可用时被高频打爆。 */
    private static final long MIN_RETRY_SECONDS = 5;

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

    public SnapshotCache(
            SnapshotProvider client,
            @Value("${app.capability.observed-ttl-seconds:60}") long ttlSeconds) {
        this.client = client;
        this.ttlSeconds = ttlSeconds > 0 ? ttlSeconds : 60;
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------
    /**
     * 取快照。不新鲜时尝试刷新一次。
     *
     * <p>返回对象的 {@code usableForExecution} 是**唯一**可用于执行准入的判据。
     */
    public SnapshotRead get() {
        SnapshotRead current = read();
        if (current.fresh()) {
            return current;
        }
        // 失败后短期内不反复重试
        if (current.lastAttemptTooRecent()) {
            return current;
        }
        return refresh();
    }

    /** 只读当前状态，<b>不触发</b>网络请求。 */
    public SnapshotRead read() {
        Entry e = this.entry;
        Instant failureAt = this.lastFailureAt;
        // 用 Duration 比较而不是 getSeconds()：后者会截断，
        // 1.2 秒在 TTL=1 时会算成 1 秒，导致"已过期"被判成"仍在 TTL 内"。
        boolean tooRecent = failureAt != null
                && Duration.between(failureAt, Instant.now())
                        .compareTo(Duration.ofSeconds(MIN_RETRY_SECONDS)) < 0;

        if (e == null) {
            return new SnapshotRead(null, false, false, "无可用快照", tooRecent,
                    "尚未成功获取过快照");
        }
        Duration age = Duration.between(e.fetchedAt(), Instant.now());
        boolean withinTtl = age.compareTo(Duration.ofSeconds(ttlSeconds)) <= 0;
        boolean fresh = !e.stale() && withinTtl;
        String detail;
        if (e.stale()) {
            detail = "快照已过期（最近一次刷新失败：" + e.lastError() + "）";
        } else if (!withinTtl) {
            detail = "快照超过 TTL（" + ttlSeconds + "s）未刷新";
        } else {
            detail = "快照新鲜";
        }
        return new SnapshotRead(e.snapshot(), fresh, fresh, detail, tooRecent, e.lastError());
    }

    // ------------------------------------------------------------------
    // 刷新
    // ------------------------------------------------------------------
    /**
     * 主动刷新一次。**同步**，保证同一时刻只有一个替换动作。
     *
     * <p>成功：ID 相同只更新时间，ID 不同整体替换。
     * 失败：保留历史快照并标记 stale；无历史则保持 {@code null}。
     */
    public synchronized SnapshotRead refresh() {
        CollectionServiceClient.FetchResult result = client.fetchSnapshot();

        if (result.ok()) {
            RegistrySnapshot incoming = result.snapshot();
            Entry prev = this.entry;
            if (prev != null && prev.snapshot().snapshotId().equals(incoming.snapshotId())) {
                // 内容未变：只刷新获取时间与健康状态
                this.entry = new Entry(prev.snapshot(), Instant.now(), false, null, null);
            } else {
                // 内容变了：整体替换，绝不做字段级合并
                this.entry = new Entry(incoming, Instant.now(), false, null, null);
                log.info("[capability] Registry 快照已更新: {}",
                        shortId(incoming.snapshotId()));
            }
            this.lastFailureAt = null;
            return read();
        }

        // 失败：保留历史，标记 stale
        Entry prev = this.entry;
        Instant now = Instant.now();
        this.lastFailureAt = now;
        if (prev != null) {
            this.entry = new Entry(prev.snapshot(), prev.fetchedAt(), true,
                    result.detail(), now);
            log.warn("[capability] 快照刷新失败（{}），保留历史快照并标记 STALE: {}",
                    result.kind(), result.detail());
        } else {
            this.entry = null;
            log.warn("[capability] 快照刷新失败（{}）且无历史可用: {}",
                    result.kind(), result.detail());
        }
        return read();
    }

    /** 清空缓存（测试用）。 */
    public synchronized void clear() {
        this.entry = null;
        this.lastFailureAt = null;
    }

    public long ttlSeconds() {
        return ttlSeconds;
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
                 String lastError, Instant lastFailureAt) {
    }

    /**
     * 读取结果。
     *
     * @param snapshot            当前快照；无可用快照时为 {@code null}
     * @param fresh               是否新鲜（未 stale 且在 TTL 内）
     * @param usableForExecution  **唯一**可用于执行准入的判据：等同 {@code fresh}
     * @param detail              人类可读说明
     * @param lastAttemptTooRecent 上次失败距今过近，本次不应再重试
     * @param lastError           最近一次失败原因
     */
    public record SnapshotRead(RegistrySnapshot snapshot, boolean fresh,
                               boolean usableForExecution, String detail,
                               boolean lastAttemptTooRecent, String lastError) {
    }
}
