package com.coolpilot.insight.collection;

import com.coolpilot.insight.collection.application.CollectionAdmissionGuard;
import com.coolpilot.insight.collection.application.impl.CollectServiceImpl;
import com.coolpilot.insight.common.exception.BizException;
import com.coolpilot.insight.common.exception.ErrorCode;
import com.coolpilot.insight.integration.capability.CollectionServiceClient;
import com.coolpilot.insight.integration.capability.RegistrySnapshotParser;
import com.coolpilot.insight.integration.capability.SnapshotCache;
import com.coolpilot.insight.integration.collection.CollectionClient;
import com.coolpilot.insight.integration.common.CommandResult;
import com.coolpilot.insight.source.application.AccountAvailability;
import com.coolpilot.insight.source.application.CapabilityReconciler;
import com.coolpilot.insight.source.application.SourceQueryService;
import com.coolpilot.insight.source.domain.DataSource;
import com.coolpilot.insight.source.domain.EffectiveCapability;
import com.coolpilot.insight.source.domain.EffectiveReason;
import com.coolpilot.insight.source.domain.EffectiveStatus;
import com.coolpilot.insight.source.dto.DataSourceView;
import com.coolpilot.insight.source.infrastructure.persistence.DataSourceMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 采集准入（SOURCE-06 · A8）。
 *
 * <p>要证明的不是"能不能采"，而是**不可执行时什么都不该发生**：
 * 不建任务、不插 item、不发 HTTP。所以第 3 个用例刻意只注入守卫，
 * 其余依赖全为 {@code null}——守卫若不是第一条语句，测试会以 NPE 而不是
 * 业务异常失败，顺序被悄悄改掉会立刻被发现。
 */
class CollectionAdmissionTest {

    private static final String SOURCE = "DOUYIN";
    private static final String CAP = "COMMENT";

    // ------------------------------------------------------------------
    // 守卫本身
    // ------------------------------------------------------------------
    @Test
    @DisplayName("快照正常且能力 AVAILABLE → 放行")
    void passesWhenAvailable() throws IOException {
        CollectionAdmissionGuard guard = guardWith(healthyCache());

        guard.requireExecutable(SOURCE, CAP);   // 不抛即通过
    }

    @Test
    @DisplayName("Registry 不可达 → 拒绝，并把 UNKNOWN/REGISTRY_UNREACHABLE 说清楚")
    void rejectsWhenRegistryUnreachable() {
        CollectionAdmissionGuard guard = guardWith(failingCache());

        assertThatThrownBy(() -> guard.requireExecutable(SOURCE, CAP))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException biz = (BizException) e;
                    assertThat(biz.getErrorCode()).isEqualTo(ErrorCode.STATE_CONFLICT);
                    assertThat(biz.getMessage()).contains("UNKNOWN").contains("REGISTRY_UNREACHABLE");
                });
    }

    @Test
    @DisplayName("能力未声明 → 拒绝，理由指向 UNREGISTERED/NOT_DECLARED 而不是笼统不可用")
    void rejectsWhenNotDeclared() throws IOException {
        CollectionAdmissionGuard guard = guardWith(healthyCache());

        assertThatThrownBy(() -> guard.requireExecutable(SOURCE, "AUTHOR_PROFILE"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getMessage())
                        .contains("UNREGISTERED").contains("NOT_DECLARED"));
    }

    @Test
    @DisplayName("需要账号 → 拒绝，理由保留 ACCOUNT_REQUIRED")
    void rejectsWhenAccountRequired() {
        DataSourceView view = viewWith(EffectiveCapability.of(
                CAP, "COMMENT", EffectiveStatus.ACCOUNT_REQUIRED,
                EffectiveReason.ACCOUNT_REQUIRED, "该能力需要登录态，但当前没有可用账号"));

        assertThatThrownBy(() -> new CollectionAdmissionGuard(null).requireExecutable(view, CAP))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getMessage())
                        .contains("ACCOUNT_REQUIRED").contains("没有可用账号"));
    }

    @Test
    @DisplayName("Desired 读不懂且无候选 → 拒绝，并把解析错误带出来")
    void rejectsWhenDesiredUnparseable() {
        DataSourceView view = new DataSourceView();
        view.setCode(SOURCE);
        view.setSnapshotState("FRESH");
        view.setDesiredError("不认识的 Desired 主版本: 3.0");
        view.setEffective(List.of());

        assertThatThrownBy(() -> new CollectionAdmissionGuard(null).requireExecutable(view, CAP))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getMessage()).contains("3.0"));
    }

    // ------------------------------------------------------------------
    // 调用点顺序：不可执行时不得发出任何 HTTP
    // ------------------------------------------------------------------
    @Test
    @DisplayName("submit：准入不通过时一个采集请求都不发（其余依赖故意为 null）")
    void submitStopsBeforeAnySideEffect() throws Exception {
        CountingClient client = new CountingClient();
        CollectServiceImpl service = new CollectServiceImpl();

        // 只注入守卫与采集客户端；videoService / mapper 等全为 null。
        // 若守卫不是第一条语句，这里会 NPE，而不是抛业务异常。
        inject(service, "admissionGuard", new CollectionAdmissionGuard(
                new SourceQueryService(
                        new FixedMapper(),
                        failingCache(),
                        (AccountAvailability) id -> true,
                        new CapabilityReconciler())));
        inject(service, "collectionClient", client);

        assertThatThrownBy(() -> service.submit(List.of("7703721523349239951"), 20, "online", 1L))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getMessage())
                        .contains("REGISTRY_UNREACHABLE"));

        assertThat(client.calls.get())
                .as("准入拒绝后不得调用采集客户端")
                .isZero();
    }

    // ------------------------------------------------------------------
    // 装配
    // ------------------------------------------------------------------
    private static void inject(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static CollectionAdmissionGuard guardWith(SnapshotCache cache) {
        SourceQueryService query = new SourceQueryService(
                new FixedMapper(), cache, (AccountAvailability) id -> true, new CapabilityReconciler());
        return new CollectionAdmissionGuard(query);
    }

    private static DataSourceView viewWith(EffectiveCapability capability) {
        DataSourceView v = new DataSourceView();
        v.setCode(SOURCE);
        v.setSnapshotState("FRESH");
        v.setEffective(List.of(capability));
        return v;
    }

    private static SnapshotCache healthyCache() throws IOException {
        String sample;
        try (InputStream in = CollectionAdmissionTest.class
                .getResourceAsStream("/registry-snapshot-sample.json")) {
            assertThat(in).as("快照样本必须存在").isNotNull();
            sample = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        var parsed = RegistrySnapshotParser.parse(sample);
        assertThat(parsed.ok()).as(parsed.ok() ? "" : parsed.error().message()).isTrue();
        return new SnapshotCache(() -> new CollectionServiceClient.FetchResult(
                parsed.snapshot(), CollectionServiceClient.FetchResult.Kind.OK, null), 60);
    }

    private static SnapshotCache failingCache() {
        return new SnapshotCache(() -> CollectionServiceClient.FetchResult.failure(
                CollectionServiceClient.FetchResult.Kind.UNREACHABLE, "ConnectException: 连接被拒绝"), 60);
    }

    private static DataSource douyin() {
        DataSource ds = new DataSource();
        ds.setId(1L);
        ds.setCode(SOURCE);
        ds.setName("抖音");
        ds.setSourceType("SHORT_VIDEO");
        ds.setAdapterCode("douyin");
        ds.setStatus("ENABLED");
        ds.setCapabilityJson("{\"schemaVersion\":\"2.0\",\"capabilities\":["
                + "{\"sourceCode\":\"DOUYIN\",\"connectorCode\":\"DOUYIN_PUBLIC\","
                + "\"code\":\"COMMENT\",\"category\":\"COMMENT\","
                + "\"accessModes\":[\"PUBLIC\",\"AUTHENTICATED\"],\"desiredStatus\":\"ENABLED\"}]}");
        return ds;
    }

    private record FixedMapper() implements DataSourceMapper {
        @Override
        public List<DataSource> selectAll() {
            return List.of(douyin());
        }

        @Override
        public DataSource selectByCode(String code) {
            return SOURCE.equals(code) ? douyin() : null;
        }

        @Override
        public DataSource selectById(Long id) {
            return douyin();
        }

        @Override
        public int updateStatus(String code, String status) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int updateCapabilityJson(String code, String json) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class CountingClient implements CollectionClient {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public CommandResult collectComments(String externalContentId, int maxComments,
                                             boolean useLogin, Long taskId, Long itemId) {
            calls.incrementAndGet();
            return new CommandResult(0, "should not be called", 0);
        }

        @Override
        public CommandResult searchByKeyword(String keyword, int max) {
            calls.incrementAndGet();
            return new CommandResult(0, "should not be called", 0);
        }
    }
}
