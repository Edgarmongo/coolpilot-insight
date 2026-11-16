package com.coolpilot.insight.source;

import com.coolpilot.insight.common.exception.BizException;
import com.coolpilot.insight.common.exception.ErrorCode;
import com.coolpilot.insight.integration.capability.CollectionServiceClient;
import com.coolpilot.insight.integration.capability.RegistrySnapshot;
import com.coolpilot.insight.integration.capability.RegistrySnapshotParser;
import com.coolpilot.insight.integration.capability.SnapshotCache;
import com.coolpilot.insight.source.application.AccountAvailability;
import com.coolpilot.insight.source.application.CapabilityReconciler;
import com.coolpilot.insight.source.application.SourceCapabilityService;
import com.coolpilot.insight.source.domain.DataSource;
import com.coolpilot.insight.source.dto.SourceCapabilityDetailView;
import com.coolpilot.insight.source.infrastructure.persistence.DataSourceMapper;
import com.coolpilot.insight.system.application.AuditService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SourceCapabilityService} 的三态装配与写路径（SOURCE-04-06）。
 *
 * <p>写路径测试的核心断言不是"调用成功了"，而是**边界**：
 * <ul>
 *   <li>校验失败 → 既不写库也不写审计（失败请求没有改变任何事实）；</li>
 *   <li>写入只走 {@code updateCapabilityJson} 一条语句，绝不触碰 {@code status}
 *       ——更不会碰 {@code ds_account.auth_status}（那张表根本不是本服务的职责）；</li>
 *   <li>审计必须留下 before/after，否则无法回溯一次配置事故。</li>
 * </ul>
 */
class SourceCapabilityServiceTest {

    private static final String SOURCE_CODE = "DOUYIN";

    private static final String V1_JSON =
            "{\"comments\": true, \"commentStatus\": \"AVAILABLE\", \"contentDetail\": true,"
                    + " \"keywordSearch\": false, \"keywordStatus\": \"PENDING\"}";

    private static final String V2_JSON = "{\"schemaVersion\":\"2.0\",\"capabilities\":["
            + "{\"sourceCode\":\"DOUYIN\",\"connectorCode\":\"DOUYIN_PUBLIC\","
            + "\"code\":\"COMMENT\",\"category\":\"COMMENT\","
            + "\"accessModes\":[\"PUBLIC\",\"AUTHENTICATED\"],\"desiredStatus\":\"ENABLED\"}]}";

    // ------------------------------------------------------------------
    // 读：三态明细
    // ------------------------------------------------------------------
    @Test
    @DisplayName("明细：Desired / Observed / Effective 同时给出，且三者来自同一份快照")
    void detailExposesAllThreeStates() throws IOException {
        var service = service(V2_JSON, healthyCache());
        SourceCapabilityDetailView v = service.detail(SOURCE_CODE);

        assertThat(v.getSourceCode()).isEqualTo(SOURCE_CODE);
        assertThat(v.getDesiredSchemaVersion()).isEqualTo("2.0");
        assertThat(v.getDesired()).hasSize(1);
        assertThat(v.getDesiredError()).isNull();

        assertThat(v.getSnapshotState()).isEqualTo("FRESH");
        assertThat(v.getSnapshotId()).startsWith("sha256:");

        // Observed 只保留该平台的真实接入：STUB 替身必须被排除（N7-1）
        assertThat(v.getObserved()).extracting(SourceCapabilityDetailView.ObservedConnector::getConnectorCode)
                .containsExactly("DOUYIN_PUBLIC");
        assertThat(v.getObserved()).allSatisfy(c -> assertThat(c.isPlatformScoped()).isTrue());

        assertThat(v.getEffective()).isNotEmpty();
    }

    @Test
    @DisplayName("明细：诊断只保留本 Connector 的记录（不得把别的接入的问题算到这条上）")
    void diagnosticsAreScopedToConnector() throws IOException {
        var v = service(V2_JSON, healthyCache()).detail(SOURCE_CODE);

        assertThat(v.getObserved()).hasSize(1);
        var diagnostics = v.getObserved().get(0).getDiagnostics();
        assertThat(diagnostics).isNotNull();
        assertThat(diagnostics).allSatisfy(d ->
                assertThat(d.connectorCode()).isEqualTo("DOUYIN_PUBLIC"));
    }

    @Test
    @DisplayName("明细：Registry 不可达 → 不臆造 Observed，Effective 判 UNKNOWN")
    void unavailableRegistryKeepsObservedEmpty() throws IOException {
        var v = service(V2_JSON, failingCache()).detail(SOURCE_CODE);

        assertThat(v.getSnapshotState()).isEqualTo("UNAVAILABLE");
        assertThat(v.getSnapshotId()).isNull();
        assertThat(v.getObserved()).isEmpty();
        assertThat(v.getEffective()).isNotEmpty();
        assertThat(v.getEffective()).allSatisfy(e -> {
            assertThat(e.effectiveStatus()).isEqualTo("UNKNOWN");
            assertThat(e.executable()).isFalse();
        });
    }

    @Test
    @DisplayName("明细：v1 行仍可读，并如实标注结构版本")
    void detailWorksForLegacyV1Row() throws IOException {
        var v = service(V1_JSON, healthyCache()).detail(SOURCE_CODE);

        assertThat(v.getDesiredSchemaVersion()).isEqualTo("1");
        assertThat(v.getDesired()).hasSize(3);
        assertThat(v.getDesiredError()).isNull();
    }

    @Test
    @DisplayName("明细：数据源不存在 → 404")
    void detailRejectsUnknownSource() throws IOException {
        var service = service(V2_JSON, healthyCache());

        assertThatThrownBy(() -> service.detail("NOPE"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ------------------------------------------------------------------
    // 写：Desired 覆盖
    // ------------------------------------------------------------------
    @Test
    @DisplayName("写：合法请求 → 落库规范化 JSON，并写 before/after 审计")
    void replaceDesiredWritesCanonicalJsonAndAudit() throws IOException {
        var mapper = new FakeMapper(source(V1_JSON));
        var audit = new RecordingAudit();
        var service = service(mapper, healthyCache(), audit);

        SourceCapabilityDetailView v = service.replaceDesired(SOURCE_CODE, V2_JSON);

        assertThat(mapper.updatedJson).isNotNull();
        assertThat(mapper.updatedJson).contains("\"schemaVersion\":\"2.0\"");
        assertThat(mapper.updatedJson).contains("\"connectorCode\":\"DOUYIN_PUBLIC\"");

        assertThat(audit.action).isEqualTo(SourceCapabilityService.ACTION_UPDATE_DESIRED);
        assertThat(audit.resourceType).isEqualTo("ds_source");
        assertThat(audit.resourceId).isEqualTo(1L);
        assertThat(audit.detail).contains("\"before\"").contains("\"after\"");
        // before 必须是改动前的真实内容，否则审计无法回溯
        assertThat(audit.detail).contains("commentStatus");

        // 返回的是落库后的真实状态
        assertThat(v.getDesired()).hasSize(1);
    }

    @Test
    @DisplayName("写：只调用 updateCapabilityJson，不触碰 status（更不碰账号状态）")
    void replaceDesiredTouchesOnlyCapabilityColumn() throws IOException {
        var mapper = new FakeMapper(source(V1_JSON));
        // FakeMapper.updateStatus 会抛异常：一旦写路径顺手改了状态，这里必红
        service(mapper, healthyCache(), new RecordingAudit())
                .replaceDesired(SOURCE_CODE, V2_JSON);

        assertThat(mapper.updateStatusCalls).isZero();
        assertThat(mapper.capabilityUpdateCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("写：校验失败 → 不写库、不审计（失败请求不产生事实）")
    void invalidWriteChangesNothing() {
        var mapper = new FakeMapper(source(V1_JSON));
        var audit = new RecordingAudit();
        String bad = "{\"schemaVersion\":\"1.0\",\"capabilities\":[]}";

        assertThatThrownBy(() -> service(mapper, healthyCache(), audit)
                .replaceDesired(SOURCE_CODE, bad))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);

        assertThat(mapper.capabilityUpdateCalls).isZero();
        assertThat(mapper.updatedJson).isNull();
        assertThat(audit.action).isNull();
    }

    @Test
    @DisplayName("写：数据源不存在 → 404，且不写库")
    void writeRejectsUnknownSource() {
        var mapper = new FakeMapper(source(V1_JSON));

        assertThatThrownBy(() -> service(mapper, healthyCache(), new RecordingAudit())
                .replaceDesired("NOPE", V2_JSON))
                .isInstanceOf(BizException.class);

        assertThat(mapper.capabilityUpdateCalls).isZero();
    }

    // ------------------------------------------------------------------
    // 装配
    // ------------------------------------------------------------------
    private static SourceCapabilityService service(String capabilityJson, SnapshotCache cache)
            throws IOException {
        return service(new FakeMapper(source(capabilityJson)), cache, new RecordingAudit());
    }

    private static SourceCapabilityService service(FakeMapper mapper, SnapshotCache cache,
                                                   AuditService audit) {
        return new SourceCapabilityService(mapper, cache,
                (AccountAvailability) sourceId -> true,
                new CapabilityReconciler(), audit);
    }


    private static SnapshotCache healthyCache() throws IOException {
        String sample;
        try (InputStream in = SourceCapabilityServiceTest.class
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

    private static SnapshotCache failingCache() {
        return new SnapshotCache(() -> CollectionServiceClient.FetchResult.failure(
                CollectionServiceClient.FetchResult.Kind.UNREACHABLE, "ConnectException: 连接被拒绝"), 60);
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

    /** 记录写调用；写后回读到的内容即"落库结果"，用于验证返回值不是请求体回声。 */
    private static final class FakeMapper implements DataSourceMapper {

        private final DataSource only;
        private String currentJson;
        private String updatedJson;
        private int capabilityUpdateCalls;
        private int updateStatusCalls;

        private FakeMapper(DataSource only) {
            this.only = only;
            this.currentJson = only.getCapabilityJson();
        }

        @Override
        public List<DataSource> selectAll() {
            return List.of(only);
        }

        @Override
        public DataSource selectByCode(String code) {
            if (!only.getCode().equals(code)) {
                return null;
            }
            only.setCapabilityJson(currentJson);
            return only;
        }

        @Override
        public DataSource selectById(Long id) {
            return only.getId().equals(id) ? only : null;
        }

        @Override
        public int updateStatus(String code, String status) {
            updateStatusCalls++;
            throw new UnsupportedOperationException("写能力声明不得改 Source 状态");
        }

        @Override
        public int updateCapabilityJson(String code, String json) {
            capabilityUpdateCalls++;
            updatedJson = json;
            currentJson = json;
            return isKnown(code) ? 1 : 0;
        }

        private boolean isKnown(String code) {
            return only.getCode().equals(code);
        }
    }

    /** 只记录最后一次审计；不触碰 DB。 */
    private static final class RecordingAudit extends AuditService {

        private String action;
        private String resourceType;
        private Long resourceId;
        private String detail;

        private RecordingAudit() {
            super(null, null, null);
        }

        @Override
        public void audit(String action, String resourceType, Long resourceId, String detailJson) {
            this.action = action;
            this.resourceType = resourceType;
            this.resourceId = resourceId;
            this.detail = detailJson;
        }
    }

}
