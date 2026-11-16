package com.coolpilot.insight.source;

import com.coolpilot.insight.integration.capability.CapabilityDocument;
import com.coolpilot.insight.integration.capability.CapabilityItem;
import com.coolpilot.insight.integration.capability.CollectionServiceClient;
import com.coolpilot.insight.integration.capability.RegistrySnapshot;
import com.coolpilot.insight.integration.capability.RegistrySnapshotParser;
import com.coolpilot.insight.integration.capability.SnapshotCache;
import com.coolpilot.insight.integration.capability.SnapshotFailure;
import com.coolpilot.insight.source.application.CapabilityReconciler;
import com.coolpilot.insight.source.domain.DataSource;
import com.coolpilot.insight.source.domain.DesiredCapability;
import com.coolpilot.insight.source.domain.DesiredCapabilitySet;
import com.coolpilot.insight.source.domain.EffectiveCapability;
import com.coolpilot.insight.source.domain.EffectiveReason;
import com.coolpilot.insight.source.domain.EffectiveStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三态核对测试（SOURCE-04-03）。
 *
 * <p>覆盖要求（任务卡验收标准）：
 * <ul>
 *   <li>**19 个当前可达 {@code effectiveReason} 逐条有正向用例**；</li>
 *   <li>{@code FUNCTION_NOT_CALLABLE} 有**不可达性**测试；</li>
 *   <li>N7-1 ~ N7-6 六条硬约束各有断言。</li>
 * </ul>
 *
 * <p>核对器不做 IO，全部用手工构造的输入，可以精确控制每一种 reason 的触发条件。
 */
class CapabilityReconcilerTest {

    private static final String CONNECTOR = "DOUYIN_PUBLIC";
    private static final String SOURCE_CODE = "DOUYIN";
    private static final String VALID_ID = "sha256:" + "a".repeat(64);

    private final CapabilityReconciler reconciler = new CapabilityReconciler();

    // ==================================================================
    // 构造辅助
    // ==================================================================
    private static DataSource source(String status) {
        DataSource ds = new DataSource();
        ds.setId(1L);
        ds.setCode(SOURCE_CODE);
        ds.setStatus(status);
        ds.setAdapterCode("douyin");
        return ds;
    }

    private static DesiredCapability desired(String code, String category, List<String> modes) {
        return new DesiredCapability(SOURCE_CODE, CONNECTOR, code, category, modes,
                DesiredCapability.ENABLED);
    }

    private static DesiredCapabilitySet desiredSet(DesiredCapability... caps) {
        return DesiredCapabilitySet.of(List.of(caps), "2.0");
    }

    private static CapabilityItem.Evidence goodEvidence() {
        return new CapabilityItem.Evidence(true, "2026-11-16T00:00:00Z",
                "REAL_PLATFORM_RUN", "hash", null, null, null);
    }

    private static CapabilityItem.Evidence rejectedEvidence(String reason) {
        return new CapabilityItem.Evidence(false, null, "REAL_PLATFORM_RUN",
                "hash", null, reason, "detail");
    }

    private static CapabilityItem cap(String code, String implStatus, CapabilityItem.Evidence ev) {
        return new CapabilityItem(code, "COMMENT", code, List.of("PUBLIC", "AUTHENTICATED"),
                implStatus, "1.0", "app.adapters.douyin.collector.collect",
                ev != null && Boolean.TRUE.equals(ev.accepted()) ? "2026-11-16T00:00:00Z" : null,
                null, null, "READ", ev);
    }

    private static CapabilityItem capWithModes(String code, String implStatus,
                                               CapabilityItem.Evidence ev, List<String> modes) {
        return new CapabilityItem(code, "COMMENT", code, modes,
                implStatus, "1.0", "app.adapters.douyin.collector.collect",
                ev != null && Boolean.TRUE.equals(ev.accepted()) ? "2026-11-16T00:00:00Z" : null,
                null, null, "READ", ev);
    }

    private static CapabilityDocument doc(String connector, String version, CapabilityItem... caps) {
        return new CapabilityDocument("1.1", SOURCE_CODE, connector, version, List.of(caps));
    }

    private static RegistrySnapshot.AdapterView adapter(String connector, String version,
                                                        boolean loaded, boolean platformScoped,
                                                        List<CapabilityItem> caps) {
        return new RegistrySnapshot.AdapterView(connector, SOURCE_CODE, version,
                loaded ? "LOADED" : "FAILED", "app.adapters.douyin.douyin_adapter",
                platformScoped, caps.size(), loaded ? null : "ADAPTER_ERROR",
                loaded ? null : "加载失败", caps);
    }

    private static RegistrySnapshot snapshot(List<RegistrySnapshot.AdapterView> adapters,
                                             List<CapabilityDocument> docs,
                                             List<RegistrySnapshot.Diagnostic> diags) {
        return new RegistrySnapshot(VALID_ID, "1.0", "1.1", "2026-11-16T00:00:00Z",
                adapters, docs, diags);
    }

    /** 正常快照：一个真实平台接入 + 一条能力。 */
    private static RegistrySnapshot snapshotWith(CapabilityItem item) {
        return snapshot(
                List.of(adapter(CONNECTOR, "1.0.0", true, true, List.of(item))),
                List.of(doc(CONNECTOR, "1.0.0", item)),
                List.of());
    }

    private static SnapshotCache.SnapshotRead fresh(RegistrySnapshot snap) {
        return new SnapshotCache.SnapshotRead(snap, SnapshotCache.SnapshotCacheState.FRESH,
                true, "快照新鲜", false, null);
    }

    private static SnapshotCache.SnapshotRead stale(RegistrySnapshot snap, SnapshotFailure f) {
        return new SnapshotCache.SnapshotRead(snap, SnapshotCache.SnapshotCacheState.STALE,
                false, "快照已过期", false, f);
    }

    private static SnapshotCache.SnapshotRead unavailable(SnapshotFailure f) {
        return new SnapshotCache.SnapshotRead(null, SnapshotCache.SnapshotCacheState.UNAVAILABLE,
                false, "无可用快照", false, f);
    }

    /** 取单条结果（只声明一条能力时用）。 */
    private EffectiveCapability one(DesiredCapabilitySet desired, SnapshotCache.SnapshotRead read,
                                    boolean hasAccount) {
        List<EffectiveCapability> out = reconciler.reconcile(
                source("ENABLED"), desired, read, hasAccount);
        assertThat(out).hasSize(1);
        return out.get(0);
    }

    private EffectiveCapability one(DesiredCapabilitySet desired, SnapshotCache.SnapshotRead read) {
        return one(desired, read, true);
    }

    private static DesiredCapabilitySet commentDesired() {
        return desiredSet(desired("COMMENT", "COMMENT", List.of("PUBLIC", "AUTHENTICATED")));
    }

    // ==================================================================
    // 19 个可达 reason：逐条正向
    // ==================================================================

    @Test
    @DisplayName("① NONE / AVAILABLE —— 全部准入条件通过")
    void reasonNone() {
        var read = fresh(snapshotWith(cap("COMMENT", "VERIFIED", goodEvidence())));
        var r = one(commentDesired(), read);

        assertThat(r.effectiveStatus()).isEqualTo(EffectiveStatus.AVAILABLE);
        assertThat(r.effectiveReason()).isEqualTo(EffectiveReason.NONE);
        assertThat(r.executable()).isTrue();
    }

    @Test
    @DisplayName("② SOURCE_DISABLED —— Source 停用，即使能力完全可用")
    void reasonSourceDisabled() {
        var read = fresh(snapshotWith(cap("COMMENT", "VERIFIED", goodEvidence())));
        var out = reconciler.reconcile(source("DISABLED"), commentDesired(), read, true);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).effectiveStatus()).isEqualTo(EffectiveStatus.SOURCE_DISABLED);
        assertThat(out.get(0).effectiveReason()).isEqualTo(EffectiveReason.SOURCE_DISABLED);
        assertThat(out.get(0).executable()).isFalse();
    }

    @Test
    @DisplayName("③ REGISTRY_UNREACHABLE —— 无可用快照")
    void reasonRegistryUnreachable() {
        var read = unavailable(SnapshotFailure.of(
                CollectionServiceClient.FetchResult.Kind.UNREACHABLE, "连接超时"));
        var r = one(commentDesired(), read);

        assertThat(r.effectiveStatus()).isEqualTo(EffectiveStatus.UNKNOWN);
        assertThat(r.effectiveReason()).isEqualTo(EffectiveReason.REGISTRY_UNREACHABLE);
        assertThat(r.executable()).isFalse();
    }

    @Test
    @DisplayName("④ SCHEMA_UNSUPPORTED —— 快照协议不合法（STALE + 协议类失败）")
    void reasonSchemaUnsupported() {
        // STALE 必须携带快照（SnapshotRead 的不变量），只是它已经过期
        var snap = snapshotWith(cap("COMMENT", "VERIFIED", goodEvidence()));
        var read = stale(snap, SnapshotFailure.of(
                CollectionServiceClient.FetchResult.Kind.SCHEMA_UNSUPPORTED, "主版本不支持"));
        var r = one(commentDesired(), read);

        assertThat(r.effectiveStatus()).isEqualTo(EffectiveStatus.UNKNOWN);
        assertThat(r.effectiveReason()).isEqualTo(EffectiveReason.SCHEMA_UNSUPPORTED);
        assertThat(r.executable()).isFalse();
    }

    @Test
    @DisplayName("④b SCHEMA_UNSUPPORTED —— Desired 不可解析")
    void reasonSchemaUnsupportedByDesired() {
        var read = fresh(snapshotWith(cap("COMMENT", "VERIFIED", goodEvidence())));
        var r = one(DesiredCapabilitySet.unsupported("schemaVersion 3.0 不支持"), read);

        assertThat(r.effectiveStatus()).isEqualTo(EffectiveStatus.UNKNOWN);
        assertThat(r.effectiveReason()).isEqualTo(EffectiveReason.SCHEMA_UNSUPPORTED);
    }

    @Test
    @DisplayName("⑤ OBSERVED_STALE —— 仅超 TTL，无失败信息")
    void reasonObservedStale() {
        var snap = snapshotWith(cap("COMMENT", "VERIFIED", goodEvidence()));
        var r = one(commentDesired(), stale(snap, null));

        assertThat(r.effectiveStatus()).isEqualTo(EffectiveStatus.UNKNOWN);
        assertThat(r.effectiveReason()).isEqualTo(EffectiveReason.OBSERVED_STALE);
        assertThat(r.executable()).isFalse();
    }

    @Test
    @DisplayName("⑥a NOT_DECLARED —— Java 未声明，Python 有")
    void reasonNotDeclared() {
        var read = fresh(snapshotWith(cap("COMMENT", "VERIFIED", goodEvidence())));
        var r = one(desiredSet(), read);   // Desired 空集合

        assertThat(r.effectiveStatus()).isEqualTo(EffectiveStatus.UNREGISTERED);
        assertThat(r.effectiveReason()).isEqualTo(EffectiveReason.NOT_DECLARED);
        assertThat(r.executable()).isFalse();
    }

    @Test
    @DisplayName("⑥b NOT_DECLARED —— desiredStatus=DISABLED 也算未声明")
    void reasonNotDeclaredBecauseDisabled() {
        var read = fresh(snapshotWith(cap("COMMENT", "VERIFIED", goodEvidence())));
        var disabled = DesiredCapabilitySet.of(List.of(
                new DesiredCapability(SOURCE_CODE, CONNECTOR, "COMMENT", "COMMENT",
                        List.of("PUBLIC"), DesiredCapability.DISABLED)), "2.0");
        var r = one(disabled, read);

        assertThat(r.effectiveReason()).isEqualTo(EffectiveReason.NOT_DECLARED);
        assertThat(r.detail()).contains("已禁用");
    }

    @Test
    @DisplayName("⑦a ADAPTER_NOT_LOADED —— 接入加载失败")
    void reasonAdapterNotLoaded() {
        var item = cap("COMMENT", "VERIFIED", goodEvidence());
        var snap = snapshot(
                List.of(adapter(CONNECTOR, "1.0.0", false, true, List.of())),
                List.of(doc(CONNECTOR, "1.0.0")),
                List.of());
        var r = one(commentDesired(), fresh(snap));

        assertThat(r.effectiveStatus()).isEqualTo(EffectiveStatus.ADAPTER_MISSING);
        assertThat(r.effectiveReason()).isEqualTo(EffectiveReason.ADAPTER_NOT_LOADED);
    }

    @Test
    @DisplayName("⑦b ADAPTER_NOT_LOADED —— 声明的接入不是真实平台接入")
    void reasonAdapterNotPlatformScoped() {
        var item = cap("COMMENT", "VERIFIED", goodEvidence());
        var snap = snapshot(
                List.of(adapter(CONNECTOR, "1.0.0", true, false, List.of(item))),
                List.of(doc(CONNECTOR, "1.0.0", item)),
                List.of());
        var r = one(commentDesired(), fresh(snap));

        assertThat(r.effectiveStatus()).isEqualTo(EffectiveStatus.ADAPTER_MISSING);
        assertThat(r.effectiveReason()).isEqualTo(EffectiveReason.ADAPTER_NOT_LOADED);
    }

    @Test
    @DisplayName("⑧ CONNECTOR_VERSION_MISMATCH —— Desired 锁定版本与实际不符")
    void reasonConnectorVersionMismatch() {
        var item = cap("COMMENT", "VERIFIED", goodEvidence());
        var snap = snapshot(
                List.of(adapter(CONNECTOR, "1.1.0", true, true, List.of(item))),
                List.of(doc(CONNECTOR, "1.1.0", item)),
                List.of());
        var locked = DesiredCapabilitySet.of(List.of(
                new DesiredCapability(SOURCE_CODE, CONNECTOR, "COMMENT", "COMMENT",
                        List.of("PUBLIC"), DesiredCapability.ENABLED, "1.0.0")), "2.0");
        var r = one(locked, fresh(snap));

        assertThat(r.effectiveStatus()).isEqualTo(EffectiveStatus.VERSION_MISMATCH);
        assertThat(r.effectiveReason()).isEqualTo(EffectiveReason.CONNECTOR_VERSION_MISMATCH);
    }

    @Test
    @DisplayName("⑨ CONTRACT_VERSION_MISMATCH —— 能力契约主版本不兼容")
    void reasonContractVersionMismatch() {
        var item = new CapabilityItem("COMMENT", "COMMENT", "评论", List.of("PUBLIC"),
                "VERIFIED", "2.0", "app.adapters.douyin.collector.collect",
                "2026-11-16T00:00:00Z", null, null, "READ", goodEvidence());
        var r = one(commentDesired(), fresh(snapshotWith(item)));

        assertThat(r.effectiveStatus()).isEqualTo(EffectiveStatus.VERSION_MISMATCH);
        assertThat(r.effectiveReason()).isEqualTo(EffectiveReason.CONTRACT_VERSION_MISMATCH);
    }

    @Test
    @DisplayName("⑩ CAPABILITY_NOT_REGISTERED —— Registry 无此能力且无诊断")
    void reasonCapabilityNotRegistered() {
        var snap = snapshot(
                List.of(adapter(CONNECTOR, "1.0.0", true, true, List.of())),
                List.of(doc(CONNECTOR, "1.0.0")),
                List.of());
        var r = one(commentDesired(), fresh(snap));

        assertThat(r.effectiveStatus()).isEqualTo(EffectiveStatus.UNREGISTERED);
        assertThat(r.effectiveReason()).isEqualTo(EffectiveReason.CAPABILITY_NOT_REGISTERED);
        assertThat(r.executable()).isFalse();
    }

    @Test
    @DisplayName("⑪ FUNCTION_UNRESOLVED —— 有声明但函数解析失败（不是 NOT_REGISTERED）")
    void reasonFunctionUnresolved() {
        var diag = new RegistrySnapshot.Diagnostic(CONNECTOR, "COMMENT",
                RegistrySnapshot.Diagnostic.REASON_INVALID_IMPLEMENTATION,
                "属性不存在或模块无法导入", "app.x.missing");
        var snap = snapshot(
                List.of(adapter(CONNECTOR, "1.0.0", true, true, List.of())),
                List.of(doc(CONNECTOR, "1.0.0")),
                List.of(diag));
        var r = one(commentDesired(), fresh(snap));

        assertThat(r.effectiveStatus()).isEqualTo(EffectiveStatus.UNREGISTERED);
        assertThat(r.effectiveReason())
                .as("函数问题不能退化成 CAPABILITY_NOT_REGISTERED")
                .isEqualTo(EffectiveReason.FUNCTION_UNRESOLVED);
    }

    @Test
    @DisplayName("⑬ CAPABILITY_DISABLED —— Python 显式禁用")
    void reasonCapabilityDisabled() {
        var r = one(commentDesired(), fresh(snapshotWith(cap("COMMENT", "DISABLED", null))));

        assertThat(r.effectiveStatus()).isEqualTo(EffectiveStatus.UNREGISTERED);
        assertThat(r.effectiveReason()).isEqualTo(EffectiveReason.CAPABILITY_DISABLED);
    }

    @Test
    @DisplayName("⑭ PLATFORM_UNSUPPORTED —— 平台不支持")
    void reasonPlatformUnsupported() {
        var r = one(commentDesired(), fresh(snapshotWith(cap("COMMENT", "UNSUPPORTED", null))));

        assertThat(r.effectiveStatus()).isEqualTo(EffectiveStatus.UNREGISTERED);
        assertThat(r.effectiveReason()).isEqualTo(EffectiveReason.PLATFORM_UNSUPPORTED);
    }

    @Test
    @DisplayName("⑮ ACCESS_MODE_UNSUPPORTED —— Observed 不覆盖 Desired 要求的模式")
    void reasonAccessModeUnsupported() {
        var item = capWithModes("COMMENT", "VERIFIED", goodEvidence(), List.of("PUBLIC"));
        var want = desiredSet(desired("COMMENT", "COMMENT", List.of("AUTHENTICATED")));
        var r = one(want, fresh(snapshotWith(item)));

        assertThat(r.effectiveStatus()).isEqualTo(EffectiveStatus.UNREGISTERED);
        assertThat(r.effectiveReason()).isEqualTo(EffectiveReason.ACCESS_MODE_UNSUPPORTED);
    }

    @Test
    @DisplayName("⑯ VERIFICATION_REQUIRED —— 已注册但从未验证")
    void reasonVerificationRequired() {
        var r = one(commentDesired(), fresh(snapshotWith(cap("COMMENT", "UNVERIFIED", null))));

        assertThat(r.effectiveStatus())
                .as("已注册未验证必须是 UNKNOWN，不是 UNREGISTERED")
                .isEqualTo(EffectiveStatus.UNKNOWN);
        assertThat(r.effectiveReason()).isEqualTo(EffectiveReason.VERIFICATION_REQUIRED);
        assertThat(r.executable()).isFalse();
    }

    @Test
    @DisplayName("⑰ EVIDENCE_EXPIRED —— 证据超过有效期")
    void reasonEvidenceExpired() {
        var item = cap("COMMENT", "UNVERIFIED", rejectedEvidence("EVIDENCE_EXPIRED"));
        var r = one(commentDesired(), fresh(snapshotWith(item)));

        assertThat(r.effectiveStatus()).isEqualTo(EffectiveStatus.UNKNOWN);
        assertThat(r.effectiveReason()).isEqualTo(EffectiveReason.EVIDENCE_EXPIRED);
        assertThat(r.executable()).isFalse();
    }

    @Test
    @DisplayName("⑱ EVIDENCE_INVALID —— 证据校验失败（非过期）")
    void reasonEvidenceInvalid() {
        var item = cap("COMMENT", "UNVERIFIED", rejectedEvidence("EVIDENCE_TAMPERED"));
        var r = one(commentDesired(), fresh(snapshotWith(item)));

        assertThat(r.effectiveStatus()).isEqualTo(EffectiveStatus.UNKNOWN);
        assertThat(r.effectiveReason()).isEqualTo(EffectiveReason.EVIDENCE_INVALID);
        assertThat(r.executable()).isFalse();
    }

    @Test
    @DisplayName("⑲ ACCOUNT_REQUIRED —— 只支持已认证访问且无可用账号")
    void reasonAccountRequired() {
        var item = capWithModes("COMMENT", "VERIFIED", goodEvidence(), List.of("AUTHENTICATED"));
        var want = desiredSet(desired("COMMENT", "COMMENT", List.of("AUTHENTICATED")));
        var r = one(want, fresh(snapshotWith(item)), false);

        assertThat(r.effectiveStatus()).isEqualTo(EffectiveStatus.ACCOUNT_REQUIRED);
        assertThat(r.effectiveReason()).isEqualTo(EffectiveReason.ACCOUNT_REQUIRED);
        assertThat(r.executable()).isFalse();
    }

    @Test
    @DisplayName("⑳ DEGRADED —— 可用但受限，允许降级执行")
    void reasonDegraded() {
        var r = one(commentDesired(), fresh(snapshotWith(cap("COMMENT", "DEGRADED", null))));

        assertThat(r.effectiveStatus()).isEqualTo(EffectiveStatus.DEGRADED);
        assertThat(r.effectiveReason()).isEqualTo(EffectiveReason.DEGRADED);
        assertThat(r.executable())
                .as("DEGRADED 是唯一在非 AVAILABLE 状态下仍允许执行的情形")
                .isTrue();
    }

    // ==================================================================
    // FUNCTION_NOT_CALLABLE：不可达性
    // ==================================================================
    @Test
    @DisplayName("⑫ FUNCTION_NOT_CALLABLE 当前不可达：诊断一律映射为 FUNCTION_UNRESOLVED")
    void functionNotCallableIsUnreachable() {
        // 枚举层面：20 个值都在，但只有 19 个可达
        assertThat(EffectiveReason.ALL).hasSize(20);
        assertThat(EffectiveReason.REACHABLE).hasSize(19);
        assertThat(EffectiveReason.REACHABLE)
                .doesNotContain(EffectiveReason.FUNCTION_NOT_CALLABLE);

        // 行为层面：无论诊断细节写什么，都只产出 FUNCTION_UNRESOLVED
        for (String detail : new String[]{
                "属性不存在或模块无法导入", "引用指向的对象不可调用", "任意未知描述"}) {
            var diag = new RegistrySnapshot.Diagnostic(CONNECTOR, "COMMENT",
                    RegistrySnapshot.Diagnostic.REASON_INVALID_IMPLEMENTATION, detail, "app.x.y");
            var snap = snapshot(
                    List.of(adapter(CONNECTOR, "1.0.0", true, true, List.of())),
                    List.of(doc(CONNECTOR, "1.0.0")),
                    List.of(diag));
            var r = one(commentDesired(), fresh(snap));

            assertThat(r.effectiveReason())
                    .as("detail=%s 时不得产出 NOT_CALLABLE（自由文本不是判据）", detail)
                    .isEqualTo(EffectiveReason.FUNCTION_UNRESOLVED);
        }
    }

    // ==================================================================
    // N7-1：只遍历真实平台接入
    // ==================================================================
    @Test
    @DisplayName("N7-1 STUB 替身不产生任何 Effective 结果")
    void n71StubProducesNoEffective() {
        var stubItem = new CapabilityItem("COMMENT", "COMMENT", "评论",
                List.of("PUBLIC"), "VERIFIED", "1.0", "app.adapters.stub.x",
                "2026-11-16T00:00:00Z", null, null, "READ", goodEvidence());

        var stubAdapter = new RegistrySnapshot.AdapterView(
                "STUB_PUBLIC", "STUB", "1.0.0", "LOADED", "app.adapters.stub",
                false, 1, null, null, List.of(stubItem));

        var realItem = cap("COMMENT", "VERIFIED", goodEvidence());
        var realAdapter = adapter(CONNECTOR, "1.0.0", true, true, List.of(realItem));

        var snap = snapshot(List.of(stubAdapter, realAdapter),
                List.of(doc(CONNECTOR, "1.0.0", realItem)),
                List.of());

        var out = reconciler.reconcile(source("ENABLED"), commentDesired(), fresh(snap), true);

        assertThat(out).hasSize(1);
        assertThat(out).allSatisfy(r ->
                assertThat(r.effectiveStatus()).isEqualTo(EffectiveStatus.AVAILABLE));
        // STUB 的 connectorCode 不得出现在结果里
        assertThat(out).extracting(EffectiveCapability::code).containsOnly("COMMENT");
    }

    @Test
    @DisplayName("N7-1 声明了 STUB 接入时判 ADAPTER_MISSING（替身不参与平台准入）")
    void n71DeclaringStubIsAdapterMissing() {
        var stubItem = new CapabilityItem("COMMENT", "COMMENT", "评论",
                List.of("PUBLIC"), "VERIFIED", "1.0", "app.adapters.stub.x",
                "2026-11-16T00:00:00Z", null, null, "READ", goodEvidence());
        var stubAdapter = new RegistrySnapshot.AdapterView(
                "STUB_PUBLIC", "STUB", "1.0.0", "LOADED", "app.adapters.stub",
                false, 1, null, null, List.of(stubItem));

        var want = DesiredCapabilitySet.of(List.of(
                new DesiredCapability("STUB", "STUB_PUBLIC", "COMMENT", "COMMENT",
                        List.of("PUBLIC"), DesiredCapability.ENABLED)), "2.0");
        var out = reconciler.reconcile(source("ENABLED"), want,
                fresh(snapshot(List.of(stubAdapter), List.of(), List.of())), true);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).effectiveReason()).isEqualTo(EffectiveReason.ADAPTER_NOT_LOADED);
    }

    // ==================================================================
    // N7-2：两个维度正交
    // ==================================================================
    @Test
    @DisplayName("N7-2(a) platformScoped=true + UNVERIFIED ⇒ 不是 AVAILABLE")
    void n72aVerifiedPlatformButUnverifiedCapability() {
        var r = one(commentDesired(), fresh(snapshotWith(cap("COMMENT", "UNVERIFIED", null))));

        assertThat(r.effectiveStatus())
                .as("真实平台接入不等于已验证")
                .isNotEqualTo(EffectiveStatus.AVAILABLE);
        assertThat(r.executable()).isFalse();
    }

    @Test
    @DisplayName("N7-2(b) VERIFIED + FRESH 仍不足：访问模式不覆盖就不可用")
    void n72bVerifiedDoesNotBypassAccessMode() {
        var item = capWithModes("COMMENT", "VERIFIED", goodEvidence(), List.of("PUBLIC"));
        var want = desiredSet(desired("COMMENT", "COMMENT", List.of("AUTHENTICATED")));
        var r = one(want, fresh(snapshotWith(item)));

        assertThat(r.effectiveStatus())
                .as("已验证不能绕过访问模式检查")
                .isNotEqualTo(EffectiveStatus.AVAILABLE);
        assertThat(r.effectiveReason()).isEqualTo(EffectiveReason.ACCESS_MODE_UNSUPPORTED);
        assertThat(r.executable()).isFalse();
    }

    @Test
    @DisplayName("N7-2(b) VERIFIED + FRESH 仍不足：Desired 未声明就不可用")
    void n72bVerifiedDoesNotBypassDesired() {
        var r = one(desiredSet(), fresh(snapshotWith(cap("COMMENT", "VERIFIED", goodEvidence()))));

        assertThat(r.effectiveStatus()).isNotEqualTo(EffectiveStatus.AVAILABLE);
        assertThat(r.effectiveReason()).isEqualTo(EffectiveReason.NOT_DECLARED);
    }

    // ==================================================================
    // N7-3 / N7-4：快照新鲜度
    // ==================================================================
    @Test
    @DisplayName("N7-3 VERIFIED 但快照 STALE ⇒ 不可执行")
    void n73VerifiedButStaleIsNotExecutable() {
        var snap = snapshotWith(cap("COMMENT", "VERIFIED", goodEvidence()));
        var r = one(commentDesired(), stale(snap, null));

        assertThat(r.effectiveStatus()).isEqualTo(EffectiveStatus.UNKNOWN);
        assertThat(r.executable()).isFalse();
    }

    @Test
    @DisplayName("N7-4 STALE / UNAVAILABLE 下不得出现 AVAILABLE")
    void n74NonFreshNeverYieldsAvailable() {
        var snap = snapshotWith(cap("COMMENT", "VERIFIED", goodEvidence()));
        var want = commentDesired();

        var staleOut = reconciler.reconcile(source("ENABLED"), want, stale(snap, null), true);
        assertThat(staleOut).isNotEmpty()
                .allSatisfy(r -> {
                    assertThat(r.effectiveStatus()).isEqualTo(EffectiveStatus.UNKNOWN);
                    assertThat(r.executable()).isFalse();
                });

        var unavailableOut = reconciler.reconcile(source("ENABLED"), want,
                unavailable(SnapshotFailure.of(
                        CollectionServiceClient.FetchResult.Kind.UNREACHABLE, "超时")), true);
        assertThat(unavailableOut).isNotEmpty()
                .allSatisfy(r -> {
                    assertThat(r.effectiveStatus()).isEqualTo(EffectiveStatus.UNKNOWN);
                    assertThat(r.executable()).isFalse();
                });
    }

    // ==================================================================
    // N7-5：白名单准入
    // ==================================================================
    @Test
    @DisplayName("N7-5 UNKNOWN 一律不可执行；只有 AVAILABLE+NONE 与 DEGRADED 可执行")
    void n75WhitelistOnly() {
        assertThat(EffectiveStatus.allowsExecution(EffectiveStatus.AVAILABLE, EffectiveReason.NONE))
                .isTrue();
        assertThat(EffectiveStatus.allowsExecution(EffectiveStatus.AVAILABLE, EffectiveReason.DEGRADED))
                .as("AVAILABLE 但带阻断原因，不可执行")
                .isFalse();
        assertThat(EffectiveStatus.allowsExecution(EffectiveStatus.DEGRADED, EffectiveReason.DEGRADED))
                .isTrue();
        for (String s : List.of(EffectiveStatus.UNKNOWN, EffectiveStatus.UNREGISTERED,
                EffectiveStatus.ADAPTER_MISSING, EffectiveStatus.VERSION_MISMATCH,
                EffectiveStatus.ACCOUNT_REQUIRED, EffectiveStatus.SOURCE_DISABLED)) {
            assertThat(EffectiveStatus.allowsExecution(s, EffectiveReason.VERIFICATION_REQUIRED))
                    .as("%s 不得可执行", s)
                    .isFalse();
        }
    }

    // ==================================================================
    // 不变量
    // ==================================================================
    @Test
    @DisplayName("EffectiveCapability 构造时拒绝 status 与 executable 不一致")
    void effectiveInvariantEnforced() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new EffectiveCapability("COMMENT", "COMMENT", EffectiveStatus.UNKNOWN,
                        EffectiveReason.VERIFICATION_REQUIRED, true, "非法"))
                .isInstanceOf(IllegalArgumentException.class);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new EffectiveCapability("COMMENT", "COMMENT", "NOT_A_STATUS",
                        EffectiveReason.NONE, false, "非法"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================================================================
    // 端到端：真实数据
    // ==================================================================
    @Test
    @DisplayName("端到端：真实 capability_json(v1) + 真实快照样本 → Effective")
    void endToEndWithRealData() throws IOException {
        // 库中 DOUYIN 的真实值（2026-11-16 实查）
        String realV1 = "{\"comments\": true, \"commentStatus\": \"AVAILABLE\","
                + " \"contentDetail\": true, \"keywordSearch\": false,"
                + " \"keywordStatus\": \"PENDING\"}";
        var desired = com.coolpilot.insight.source.application.DesiredCapabilityParser
                .parse(realV1, "DOUYIN");
        assertThat(desired.supported()).isTrue();

        // 真实容器导出的快照
        String sample;
        try (InputStream in = getClass().getResourceAsStream("/registry-snapshot-sample.json")) {
            assertThat(in).as("快照样本必须存在").isNotNull();
            sample = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        var parsed = RegistrySnapshotParser.parse(sample);
        assertThat(parsed.ok()).as(parsed.error() == null ? "" : parsed.error().message()).isTrue();

        var out = reconciler.reconcile(source("ENABLED"), desired, fresh(parsed.snapshot()), true);

        // Desired ∪ Observed = {COMMENT, CONTENT_DETAIL, KEYWORD_SEARCH, COMMENT_REPLY}
        assertThat(out).extracting(EffectiveCapability::code)
                .containsExactlyInAnyOrder("COMMENT", "CONTENT_DETAIL",
                        "KEYWORD_SEARCH", "COMMENT_REPLY");

        // 声明且已验证 → 可用
        assertThat(out).filteredOn(r -> "COMMENT".equals(r.code())).singleElement()
                .satisfies(r -> {
                    assertThat(r.effectiveStatus()).isEqualTo(EffectiveStatus.AVAILABLE);
                    assertThat(r.executable()).isTrue();
                });
        assertThat(out).filteredOn(r -> "CONTENT_DETAIL".equals(r.code())).singleElement()
                .satisfies(r -> assertThat(r.executable()).isTrue());

        // v1 里 keywordSearch=false → 业务未启用
        assertThat(out).filteredOn(r -> "KEYWORD_SEARCH".equals(r.code())).singleElement()
                .satisfies(r -> assertThat(r.effectiveReason())
                        .isEqualTo(EffectiveReason.NOT_DECLARED));

        // **v1 的局限**：COMMENT_REPLY 在 Python 侧已 VERIFIED，但 Java 的 v1
        // capability_json 根本没有这个字段 → 判 NOT_DECLARED，不可执行。
        // 这不是缺陷，是"Desired 结构落后于 Observed"的真实体现，
        // 迁移到 v2 后应补上声明。
        assertThat(out).filteredOn(r -> "COMMENT_REPLY".equals(r.code())).singleElement()
                .satisfies(r -> {
                    assertThat(r.effectiveReason()).isEqualTo(EffectiveReason.NOT_DECLARED);
                    assertThat(r.executable()).isFalse();
                });
    }

    @Test
    @DisplayName("候选集合取 Desired ∪ Observed：两边都漏不掉")
    void candidateUnionCoversBothSides() {
        // Desired 声明 2 条，Observed 有 1 条（同一 CONNECTOR，不同 code）
        var observed = cap("COMMENT", "VERIFIED", goodEvidence());
        var snap = snapshot(
                List.of(adapter(CONNECTOR, "1.0.0", true, true, List.of(observed))),
                List.of(doc(CONNECTOR, "1.0.0", observed)),
                List.of());
        var want = desiredSet(
                desired("COMMENT", "COMMENT", List.of("PUBLIC")),
                desired("CONTENT_DETAIL", "CONTENT", List.of("PUBLIC")));

        var out = reconciler.reconcile(source("ENABLED"), want, fresh(snap), true);

        assertThat(out).extracting(EffectiveCapability::code)
                .containsExactlyInAnyOrder("COMMENT", "CONTENT_DETAIL");
    }
}
