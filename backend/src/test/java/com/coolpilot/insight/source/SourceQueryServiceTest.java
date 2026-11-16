package com.coolpilot.insight.source;

import com.coolpilot.insight.integration.capability.CollectionServiceClient;
import com.coolpilot.insight.integration.capability.RegistrySnapshot;
import com.coolpilot.insight.integration.capability.RegistrySnapshotParser;
import com.coolpilot.insight.integration.capability.SnapshotCache;
import com.coolpilot.insight.source.application.AccountAvailability;
import com.coolpilot.insight.source.application.CapabilityReconciler;
import com.coolpilot.insight.source.application.SourceQueryService;
import com.coolpilot.insight.source.domain.DataSource;
import com.coolpilot.insight.source.dto.DataSourceView;
import com.coolpilot.insight.source.infrastructure.persistence.DataSourceMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SourceQueryService} 的三态视图装配测试（SOURCE-04-03-FIX-04 · F2-3）。
 *
 * <p>为什么必须有这一层测试：`desiredError` 是"Desired 读不懂"时调用方**唯一**的
 * 诊断依据——那时 Effective 可能一行都没有。之前它只有代码、没有断言，
 * 被误改成 null 也不会有测试变红，M1 的闭环会静默失效（独立复核 F2-3）。
 *
 * <p>四个场景对应评审要求：
 * <ol>
 *   <li>A：Desired 正常 → {@code desiredError == null}</li>
 *   <li>B：Desired 坏、Registry 正常 → {@code desiredError != null}，所有能力不可执行</li>
 *   <li>C：Desired 坏、Registry 不可达 → {@code desiredError != null} **且** Registry 故障仍可识别</li>
 *   <li>D：不伪造 EffectiveCapability、不产生可执行能力</li>
 * </ol>
 */
class SourceQueryServiceTest {

    private static final String SOURCE_CODE = "DOUYIN";

    /** 库中真实 v1（合法）。 */
    private static final String VALID_V1 =
            "{\"comments\": true, \"commentStatus\": \"AVAILABLE\", \"contentDetail\": true,"
                    + " \"keywordSearch\": false, \"keywordStatus\": \"PENDING\"}";

    /** 主版本 3：Desired 解析器认识不了。 */
    private static final String UNPARSEABLE_V1 = "{\"schemaVersion\": \"3.0\", \"capabilities\": []}";

    // ------------------------------------------------------------------
    // A：Desired 正常
    // ------------------------------------------------------------------
    @Test
    @DisplayName("A Desired 正常 + Registry 正常 → desiredError=null，已验证能力可执行")
    void healthyDesiredAndRegistry() throws IOException {
        var view = single(VALID_V1, healthyCache());

        assertThat(view.getDesiredError()).isNull();
        assertThat(view.getSnapshotState()).isEqualTo("FRESH");
        assertThat(view.getDesired()).hasSize(3);
        assertThat(view.getCapability())
                .as("兼容期旧结构必须仍在")
                .isNotNull();

        assertThat(view.getEffective())
                .filteredOn(e -> "COMMENT".equals(e.code())).singleElement()
                .satisfies(e -> {
                    assertThat(e.effectiveStatus()).isEqualTo("AVAILABLE");
                    assertThat(e.executable()).isTrue();
                });
    }

    // ------------------------------------------------------------------
    // B：Desired 坏、Registry 正常
    // ------------------------------------------------------------------
    @Test
    @DisplayName("B Desired 坏 + Registry 正常 → desiredError 非空，且没有任何能力可执行")
    void unparseableDesiredWithHealthyRegistry() throws IOException {
        var view = single(UNPARSEABLE_V1, healthyCache());

        assertThat(view.getDesiredError())
                .as("Desired 读不懂时必须能诊断，否则调用方只看到空列表")
                .isNotNull()
                .contains("3.0");   // 报错里带上是哪个版本读不懂

        assertThat(view.getDesired())
                .as("Desired 解析失败 → 声明列表为空")
                .isEmpty();

        // Registry 正常，所以能力码能从 Observed 拿到，逐条给出 UNKNOWN + DESIRED_UNPARSEABLE。
        // 这不是"多给了行"，而是让每一条能力都明确不可用。
        assertThat(view.getEffective()).isNotEmpty();
        assertThat(view.getEffective()).allSatisfy(e -> {
            assertThat(e.effectiveStatus()).isEqualTo("UNKNOWN");
            assertThat(e.effectiveReason()).isEqualTo("DESIRED_UNPARSEABLE");
            assertThat(e.executable()).isFalse();
        });
    }

    // ------------------------------------------------------------------
    // C + D：两边都坏
    // ------------------------------------------------------------------
    @Test
    @DisplayName("C Desired 坏 + Registry 不可达 → 两个故障域都能识别（不被彼此覆盖）")
    void bothFaultDomainsRemainVisible() throws IOException {
        var view = single(UNPARSEABLE_V1, failingCache());

        // Desired 侧
        assertThat(view.getDesiredError()).isNotNull();

        // Registry 侧：不能因为 Desired 也坏了就把服务故障藏起来
        assertThat(view.getSnapshotState())
                .as("Registry 故障必须仍然可见")
                .isEqualTo("UNAVAILABLE");
        assertThat(view.getSnapshotDetail()).isNotBlank();
    }

    @Test
    @DisplayName("D 两边都坏 → 不伪造 EffectiveCapability，且没有任何可执行能力")
    void noFabricatedCapabilitiesWhenBothBroken() throws IOException {
        var view = single(UNPARSEABLE_V1, failingCache());

        // Desired 无能力码、Observed 无骨架 ⇒ 无候选。
        // 不能为了"展示点什么"就凭空造一条记录——那会变成凭空的结论。
        assertThat(view.getEffective())
                .as("没有可靠依据时不得产出任何 Effective 行")
                .isEmpty();

        assertThat(view.getEffective())
                .allSatisfy(e -> assertThat(e.executable()).isFalse());
    }

    // ------------------------------------------------------------------
    // 装配
    // ------------------------------------------------------------------
    /** 用真实快照样本 + 真实解析器构造健康的缓存。 */
    private static SnapshotCache healthyCache() throws IOException {
        String sample;
        try (InputStream in = SourceQueryServiceTest.class
                .getResourceAsStream("/registry-snapshot-sample.json")) {
            assertThat(in).as("快照样本必须存在").isNotNull();
            sample = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        var parsed = RegistrySnapshotParser.parse(sample);
        assertThat(parsed.ok()).as(parsed.ok() ? "" : parsed.error().message()).isTrue();
        RegistrySnapshot snapshot = parsed.snapshot();

        return new SnapshotCache(() -> new CollectionServiceClient.FetchResult(
                snapshot, CollectionServiceClient.FetchResult.Kind.OK, null), 60);
    }

    /** 采集服务不可达：缓存无历史，get() 返回 UNAVAILABLE。 */
    private static SnapshotCache failingCache() {
        return new SnapshotCache(() -> CollectionServiceClient.FetchResult.failure(
                CollectionServiceClient.FetchResult.Kind.UNREACHABLE, "ConnectException: 连接被拒绝"), 60);
    }

    private static DataSourceView single(String capabilityJson, SnapshotCache cache) {
        var service = new SourceQueryService(
                new FixedMapper(source(capabilityJson)),
                cache,
                (AccountAvailability) sourceId -> true,
                new CapabilityReconciler());

        List<DataSourceView> views = service.list();
        assertThat(views).hasSize(1);
        return views.get(0);
    }

    private static DataSource source(String capabilityJson) {
        DataSource ds = new DataSource();
        ds.setId(1L);
        ds.setCode(SOURCE_CODE);
        ds.setName("抖音");
        ds.setSourceType("SHORT_VIDEO");
        ds.setAdapterCode("douyin");
        ds.setStatus("ENABLED");
        ds.setCapabilityJson(capabilityJson);
        return ds;
    }

    /** 只实现 list()/findByCode() 用得到的两个方法。 */
    private record FixedMapper(DataSource only) implements DataSourceMapper {

        @Override
        public List<DataSource> selectAll() {
            return List.of(only);
        }

        @Override
        public DataSource selectByCode(String code) {
            return only.getCode().equals(code) ? only : null;
        }

        @Override
        public DataSource selectById(Long id) {
            return only.getId().equals(id) ? only : null;
        }

        @Override
        public int updateStatus(String code, String status) {
            throw new UnsupportedOperationException("测试不需要改状态");
        }
    }
}
