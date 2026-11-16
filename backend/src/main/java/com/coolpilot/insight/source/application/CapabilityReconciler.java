package com.coolpilot.insight.source.application;

import com.coolpilot.insight.integration.capability.CapabilityItem;
import com.coolpilot.insight.integration.capability.CapabilityStatus;
import com.coolpilot.insight.integration.capability.RegistrySnapshot;
import com.coolpilot.insight.integration.capability.SnapshotCache;
import com.coolpilot.insight.integration.capability.SnapshotFailure;
import com.coolpilot.insight.source.domain.DataSource;
import com.coolpilot.insight.source.domain.DesiredCapability;
import com.coolpilot.insight.source.domain.DesiredCapabilitySet;
import com.coolpilot.insight.source.domain.EffectiveCapability;
import com.coolpilot.insight.source.domain.EffectiveReason;
import com.coolpilot.insight.source.domain.EffectiveStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Desired / Observed / Effective 三态核对（ADR-006）。
 *
 * <h3>三条不可违反的约束</h3>
 * <ol>
 *   <li><b>只遍历真实平台接入</b>（N7-1）：用 {@code snapshot.platformScopedAdapters()}，
 *       不用 {@code snapshot.adapters()}。STUB 替身不得进入真实平台能力准入。</li>
 *   <li><b>只有 FRESH 快照可参与</b>（N7-4）：STALE / UNAVAILABLE 一律判 {@code UNKNOWN}，
 *       不产出 {@code AVAILABLE}。禁止用 {@code snapshot != null} 判断可用性。</li>
 *   <li><b>白名单准入</b>（N7-5）：见 {@link EffectiveStatus#allowsExecution}。</li>
 * </ol>
 *
 * <h3>证据闸门</h3>
 * 第 ⑭~⑯ 步对**所有未被 ⑪⑫ 拦下的实现状态**生效，`IMPLEMENTED` 与 `DEGRADED`
 * 不例外（`SOURCE-04-04-TASKCARD.md` §3.2.1）。`IMPLEMENTED` 表示"本地测试通过"，
 * 与 `VERIFIED`（真实平台运行验证）不是同一件事，因此**永不产出** {@code AVAILABLE}。
 *
 * <h3>判定顺序</h3>
 * 严格按 `SOURCE-04-04-EFFECTIVE-REASON.md` §4 自上而下短路，<b>不得为实现方便调整</b>。
 * 顺序本身就是优先级声明：先判"根本没依据"（服务/结构/新鲜度），再判"业务没要"，
 * 再判"接入坏了"，最后才判能力自身的实现与验证。
 *
 * <p>本类**不做任何 IO**，输入齐备即可纯函数式判定，便于穷举测试。
 */
@Component
public class CapabilityReconciler {

    private static final String MODE_PUBLIC = "PUBLIC";
    private static final String MODE_AUTHENTICATED = "AUTHENTICATED";

    /** Java 支持的能力契约主版本（与 SOURCE-04-04 §4.2 一致）。 */
    private static final int SUPPORTED_CONTRACT_MAJOR = 1;

    /** 证据被拒绝时，哪些原因归为"过期"、哪些归为"无效"（ADR-008 §3.3）。 */
    private static final String EVIDENCE_EXPIRED = "EVIDENCE_EXPIRED";

    /**
     * 核对一个 Source 的全部能力。
     *
     * @param source           数据源（用其 {@code status}；可为 null，视为启用）
     * @param desired          Desired 集合
     * @param read             当前快照读取结果（含新鲜度）
     * @param hasUsableAccount 该 Source 下是否有可用账号
     * @return 每条能力的 Effective 结论；无候选时返回空列表
     */
    public List<EffectiveCapability> reconcile(DataSource source,
                                               DesiredCapabilitySet desired,
                                               SnapshotCache.SnapshotRead read,
                                               boolean hasUsableAccount) {
        DesiredCapabilitySet desiredSafe = desired == null
                ? DesiredCapabilitySet.unsupported("Desired 缺失")
                : desired;

        List<Candidate> candidates = collectCandidates(desiredSafe, read);
        if (candidates.isEmpty()) {
            return List.of();
        }

        // ---------------- 全局短路：这几种情况下逐条判定没有意义 ----------------
        Blocked global = globalBlock(source, desiredSafe, read);
        if (global != null) {
            List<EffectiveCapability> out = new ArrayList<>(candidates.size());
            for (Candidate c : candidates) {
                out.add(EffectiveCapability.of(c.code, c.category,
                        global.status, global.reason, global.detail));
            }
            return out;
        }

        RegistrySnapshot snap = read.snapshot();
        Map<String, RegistrySnapshot.AdapterView> adapters = platformScopedAdapters(snap);

        List<EffectiveCapability> out = new ArrayList<>(candidates.size());
        for (Candidate c : candidates) {
            out.add(evaluate(desiredSafe, snap, adapters, c, hasUsableAccount));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 全局短路（§4 第 1~4 步）
    // ------------------------------------------------------------------
    private Blocked globalBlock(DataSource source, DesiredCapabilitySet desired,
                                SnapshotCache.SnapshotRead read) {
        // ① Source 已停用
        if (source != null && !"ENABLED".equals(source.getStatus())) {
            return new Blocked(EffectiveStatus.SOURCE_DISABLED,
                    EffectiveReason.SOURCE_DISABLED, "Source 已停用，不允许新执行");
        }
        // Desired 不可解析 → 无法核对
        if (!desired.supported()) {
            return new Blocked(EffectiveStatus.UNKNOWN,
                    EffectiveReason.SCHEMA_UNSUPPORTED, "Desired 不可解析: " + desired.error());
        }
        // ②③④ 快照状态
        if (read == null || read.state() == SnapshotCache.SnapshotCacheState.UNAVAILABLE) {
            String detail = read == null ? "无快照读取结果" : read.detail();
            return new Blocked(EffectiveStatus.UNKNOWN,
                    EffectiveReason.REGISTRY_UNREACHABLE, detail);
        }
        if (read.state() != SnapshotCache.SnapshotCacheState.FRESH) {
            // STALE：区分"服务不可达"与"响应不合法"，二者都是 UNKNOWN 但原因不同
            return new Blocked(EffectiveStatus.UNKNOWN, staleReason(read), read.detail());
        }
        return null;
    }

    /** STALE 时按失败分类给出精确原因（ADR-008：不能用自由文本判断）。 */
    private String staleReason(SnapshotCache.SnapshotRead read) {
        SnapshotFailure f = read.failure();
        if (f == null) {
            return EffectiveReason.OBSERVED_STALE;   // 单纯超 TTL
        }
        if (f.isUnreachable()) {
            return EffectiveReason.REGISTRY_UNREACHABLE;
        }
        if (f.isProtocolProblem()) {
            return EffectiveReason.SCHEMA_UNSUPPORTED;
        }
        return EffectiveReason.OBSERVED_STALE;
    }

    // ------------------------------------------------------------------
    // 逐条判定（§4 第 5~19 步）
    // ------------------------------------------------------------------
    private EffectiveCapability evaluate(DesiredCapabilitySet desired,
                                         RegistrySnapshot snap,
                                         Map<String, RegistrySnapshot.AdapterView> adapters,
                                         Candidate c,
                                         boolean hasUsableAccount) {
        // ⑤ Desired 未声明（不存在，或 desiredStatus=DISABLED）
        java.util.Optional<DesiredCapability> decl = desired.find(c.connectorCode, c.code);
        if (decl.isEmpty() || !decl.get().enabled()) {
            String why = decl.isPresent()
                    ? "业务已禁用该能力（desiredStatus=DISABLED）"
                    : "Java 未声明该能力";
            return EffectiveCapability.of(c.code, c.category,
                    EffectiveStatus.UNREGISTERED, EffectiveReason.NOT_DECLARED, why);
        }
        DesiredCapability dc = decl.get();

        // ⑥ Adapter 未加载 / 不是真实平台接入
        RegistrySnapshot.AdapterView adapter = adapters.get(c.connectorCode);
        if (adapter == null) {
            return EffectiveCapability.of(c.code, c.category,
                    EffectiveStatus.ADAPTER_MISSING, EffectiveReason.ADAPTER_NOT_LOADED,
                    "接入 " + c.connectorCode + " 不是真实平台接入或未加载（platformScoped 视角）");
        }
        if (!adapter.isLoaded()) {
            return EffectiveCapability.of(c.code, c.category,
                    EffectiveStatus.ADAPTER_MISSING, EffectiveReason.ADAPTER_NOT_LOADED,
                    "接入 " + c.connectorCode + " 加载失败: " + adapter.errorMessage());
        }

        // ⑦ Connector 版本（仅在 Desired 锁定版本时判定）
        if (dc.adapterVersion() != null && !dc.adapterVersion().isBlank()
                && !dc.adapterVersion().equals(adapter.connectorVersion())) {
            return EffectiveCapability.of(c.code, c.category,
                    EffectiveStatus.VERSION_MISMATCH, EffectiveReason.CONNECTOR_VERSION_MISMATCH,
                    "锁定版本 " + dc.adapterVersion() + "，实际 " + adapter.connectorVersion());
        }

        // ⑧ 能力契约主版本
        CapabilityItem observed = findObserved(snap, c.connectorCode, c.code);
        if (observed != null && majorOf(observed.contractVersion()) != SUPPORTED_CONTRACT_MAJOR
                && majorOf(observed.contractVersion()) > 0) {
            return EffectiveCapability.of(c.code, c.category,
                    EffectiveStatus.VERSION_MISMATCH, EffectiveReason.CONTRACT_VERSION_MISMATCH,
                    "能力契约版本 " + observed.contractVersion() + " 主版本不兼容");
        }

        // ⑨ Registry 中不存在该能力
        if (observed == null) {
            RegistrySnapshot.Diagnostic diag = snap.findDiagnostic(c.connectorCode, c.code);
            if (diag != null && diag.isFunctionProblem()) {
                // ⑩ 有诊断说明"声明了但函数不成立"——这不是"没注册"
                return EffectiveCapability.of(c.code, c.category,
                        EffectiveStatus.UNREGISTERED, EffectiveReason.FUNCTION_UNRESOLVED,
                        "实现函数无法解析: " + diag.detail());
            }
            return EffectiveCapability.of(c.code, c.category,
                    EffectiveStatus.UNREGISTERED, EffectiveReason.CAPABILITY_NOT_REGISTERED,
                    "Registry 中不存在该能力，且无相关诊断");
        }

        // ⑪⑫ Python 显式禁用 / 平台不支持
        String impl = observed.implementationStatus();
        if ("DISABLED".equals(impl)) {
            return EffectiveCapability.of(c.code, c.category,
                    EffectiveStatus.UNREGISTERED, EffectiveReason.CAPABILITY_DISABLED,
                    "该能力在采集服务侧被显式禁用");
        }
        if ("UNSUPPORTED".equals(impl)) {
            return EffectiveCapability.of(c.code, c.category,
                    EffectiveStatus.UNREGISTERED, EffectiveReason.PLATFORM_UNSUPPORTED,
                    "平台不支持该能力");
        }

        // ⑬ 访问模式不覆盖
        List<String> usableModes = intersectModes(dc.accessModes(), observed.accessModes());
        if (usableModes.isEmpty()) {
            return EffectiveCapability.of(c.code, c.category,
                    EffectiveStatus.UNREGISTERED, EffectiveReason.ACCESS_MODE_UNSUPPORTED,
                    "Desired 要求 " + dc.accessModes() + "，Observed 仅支持 " + observed.accessModes());
        }

        // ⑭⑮⑯ 验证证据 —— **对所有未被 ⑪⑫ 拦下的状态一律生效**
        //
        // 首版只在 UNVERIFIED / VERIFIED 时校验，导致 IMPLEMENTED 与 DEGRADED
        // 整段跳过证据校验，落入第 ⑲ 步判 AVAILABLE；`evidence.accepted=false`
        // 也被静默放行——这是失败开放（SOURCE-04-03 评审 H1）。
        //
        // 裁定见 SOURCE-04-04-TASKCARD §3.2.1「实现状态 × 证据矩阵」：
        // 不存在"某个实现状态可以豁免证据校验"。
        CapabilityItem.Evidence ev = observed.evidence();
        if (ev == null) {
            return EffectiveCapability.of(c.code, c.category,
                    EffectiveStatus.UNKNOWN, EffectiveReason.VERIFICATION_REQUIRED,
                    missingEvidenceDetail(impl));
        }
        if (!Boolean.TRUE.equals(ev.accepted())) {
            String reason = EVIDENCE_EXPIRED.equals(ev.rejectionReason())
                    ? EffectiveReason.EVIDENCE_EXPIRED
                    : EffectiveReason.EVIDENCE_INVALID;
            return EffectiveCapability.of(c.code, c.category,
                    EffectiveStatus.UNKNOWN, reason,
                    "验证证据不被接受: " + ev.rejectionReason() + " " + ev.rejectionDetail());
        }
        // 证据有效，但实现状态只到"本地测试通过"：
        // 本地测试 ≠ 真实平台验证，因此仍不产出 AVAILABLE（§3.2.1 铁律 2）。
        if (CapabilityStatus.IMPLEMENTED.equals(impl)) {
            return EffectiveCapability.of(c.code, c.category,
                    EffectiveStatus.UNKNOWN, EffectiveReason.VERIFICATION_REQUIRED,
                    "实现状态为 IMPLEMENTED（本地测试通过），尚未取得真实平台验证");
        }

        // ⑰ 需要认证但无可用账号（仅当没有 PUBLIC 可用时的确无法执行）
        if (usableModes.contains(MODE_AUTHENTICATED) && !usableModes.contains(MODE_PUBLIC)
                && !hasUsableAccount) {
            return EffectiveCapability.of(c.code, c.category,
                    EffectiveStatus.ACCOUNT_REQUIRED, EffectiveReason.ACCOUNT_REQUIRED,
                    "该能力只支持已认证访问，但没有可用账号");
        }

        // ⑱ 可用但受限
        if ("DEGRADED".equals(impl)) {
            return EffectiveCapability.of(c.code, c.category,
                    EffectiveStatus.DEGRADED, EffectiveReason.DEGRADED,
                    "可用但受限（分页/限流等）");
        }

        // ⑲ 正常可用 —— 必须前面全部通过
        return EffectiveCapability.of(c.code, c.category,
                EffectiveStatus.AVAILABLE, EffectiveReason.NONE, "全部准入条件通过");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------
    /**
     * 候选集合 = Desired ∪ Observed（真实平台接入）。
     *
     * <p>必须取并集：只看 Desired 会漏掉 {@code NOT_DECLARED}（Python 有、Java 没登记）；
     * 只看 Observed 会漏掉"声明了但没加载"。
     */
    private List<Candidate> collectCandidates(DesiredCapabilitySet desired,
                                              SnapshotCache.SnapshotRead read) {
        Map<String, Candidate> merged = new LinkedHashMap<>();

        for (DesiredCapability d : desired.capabilities()) {
            merged.putIfAbsent(key(d.connectorCode(), d.code()),
                    new Candidate(d.connectorCode(), d.code(), d.category()));
        }

        if (read != null && read.snapshot() != null) {
            for (RegistrySnapshot.AdapterView a : read.snapshot().platformScopedAdapters()) {
                for (CapabilityItem item : a.capabilities()) {
                    merged.putIfAbsent(key(a.connectorCode(), item.code()),
                            new Candidate(a.connectorCode(), item.code(), item.category()));
                }
            }
        }
        return new ArrayList<>(merged.values());
    }

    /** N7-1：只取真实平台接入，STUB 替身不在其中。 */
    private Map<String, RegistrySnapshot.AdapterView> platformScopedAdapters(RegistrySnapshot snap) {
        Map<String, RegistrySnapshot.AdapterView> map = new LinkedHashMap<>();
        if (snap == null) {
            return map;
        }
        for (RegistrySnapshot.AdapterView a : snap.platformScopedAdapters()) {
            map.put(a.connectorCode(), a);
        }
        return map;
    }

    private CapabilityItem findObserved(RegistrySnapshot snap, String connectorCode, String code) {
        if (snap == null) {
            return null;
        }
        var doc = snap.findConnector(connectorCode);
        return doc == null ? null : doc.find(code);
    }

    private List<String> intersectModes(List<String> desired, List<String> observed) {
        List<String> out = new ArrayList<>();
        if (desired == null || observed == null) {
            return out;
        }
        for (String m : desired) {
            if (observed.contains(m)) {
                out.add(m);
            }
        }
        return out;
    }

    private static String key(String connectorCode, String code) {
        return (connectorCode == null ? "" : connectorCode) + "/" + code;
    }

    /**
     * 证据缺失时的说明。
     *
     * <p>`VERIFIED` 却没有证据属于**协议异常**（Python 侧证据被接受才升 `VERIFIED`），
     * 单独点明，避免和"从未验证"混为一谈——两者都是 `VERIFICATION_REQUIRED`，
     * 但排查方向完全不同。
     */
    private static String missingEvidenceDetail(String impl) {
        if (CapabilityStatus.VERIFIED.equals(impl)) {
            return "实现状态为 VERIFIED 但缺少验证证据（协议异常，应检查采集服务证据链路）";
        }
        if (CapabilityStatus.DEGRADED.equals(impl)) {
            return "降级能力缺少验证证据，未经验证不得降级执行";
        }
        return "能力已注册但从未获得有效验证证据";
    }

    private static int majorOf(String version) {
        if (version == null || version.isBlank()) {
            return 0;
        }
        int dot = version.indexOf('.');
        String head = dot > 0 ? version.substring(0, dot) : version;
        try {
            return Integer.parseInt(head.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 候选能力（去重键为 connectorCode+code）。 */
    private record Candidate(String connectorCode, String code, String category) {
    }

    /** 全局短路的结论。 */
    private record Blocked(String status, String reason, String detail) {
    }
}
