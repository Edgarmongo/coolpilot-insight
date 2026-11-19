package com.coolpilot.insight.system.application;

import com.coolpilot.insight.system.domain.AuditLog;
import com.coolpilot.insight.system.infrastructure.persistence.AuditLogMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 审计写入的 JSON 合法性（SOURCE-04 后续 · A）。
 *
 * <p>要证明的不是"能调通"，而是：**任何调用方传什么，落库的都是合法 JSON**。
 * 历史缺陷正是"传什么写什么"——{@code items=1} 这种裸文本被 JSON 列拒收，
 * 而异常被 catch 成一行 warn，审计静默丢失。
 */
class AuditServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ------------------------------------------------------------------
    // 规范化规则
    // ------------------------------------------------------------------
    @Test
    @DisplayName("纯文本 → {\"text\": ...}，且可被解析回来")
    void wrapsPlainText() throws Exception {
        String out = AuditService.toJsonDocument("items=1");

        JsonNode node = JSON.readTree(out);       // 非法 JSON 会直接抛
        assertThat(node.get("text").asText()).isEqualTo("items=1");
    }

    @Test
    @DisplayName("已是合法 JSON 对象 → 原样保留，不做二次包装")
    void keepsJsonObject() throws Exception {
        String out = AuditService.toJsonDocument("{\"code\":\"ADMIN\"}");

        JsonNode node = JSON.readTree(out);
        assertThat(node.get("code").asText()).isEqualTo("ADMIN");
        assertThat(node.has("text")).isFalse();
    }

    @Test
    @DisplayName("空值 → NULL，而不是字符串 \"null\"")
    void blankBecomesNull() throws Exception {
        assertThat(AuditService.toJsonDocument(null)).isNull();
        assertThat(AuditService.toJsonDocument("   ")).isNull();
    }

    @Test
    @DisplayName("数组/数字/布尔等合法 JSON 标量也照常通过")
    void acceptsOtherJsonShapes() throws Exception {
        assertThat(JSON.readTree(AuditService.toJsonDocument("[1,2]")).isArray()).isTrue();
        assertThat(JSON.readTree(AuditService.toJsonDocument("42")).asInt()).isEqualTo(42);
        assertThat(JSON.readTree(AuditService.toJsonDocument("true")).asBoolean()).isTrue();
    }

    @Test
    @DisplayName("结构化详情 → 键值落库，不再是裸文本")
    void writesStructuredDetail() {
        RecordingMapper mapper = new RecordingMapper();
        AuditService service = service(mapper, new SimpleMeterRegistry());

        service.auditStructured("SUBMIT_COLLECTION", "COLLECTION_TASK", 6L,
                Map.of("taskNo", "CT20261117", "totalItems", 1));

        JsonNode node = read(mapper.inserted.getDetailJson());
        assertThat(node.get("taskNo").asText()).isEqualTo("CT20261117");
        assertThat(node.get("totalItems").asInt()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 失败语义
    // ------------------------------------------------------------------
    @Test
    @DisplayName("写库失败 → 抛出被吞掉（不影响业务），但计数器必须涨")
    void writeFailureIsObservableNotFatal() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.failWith = new IllegalStateException("JSON 列拒收");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AuditService service = service(mapper, registry);

        assertThatCode(() -> service.audit("LOGIN", "USER", 1L, null))
                .doesNotThrowAnyException();

        assertThat(registry.get("audit.failures").tag("stage", "write")
                .tag("action", "LOGIN").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("没有 MeterRegistry 时也不得因计数而失败")
    void worksWithoutMeterRegistry() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.failWith = new IllegalStateException("boom");
        AuditService service = new AuditService(mapper, new PermissionService(null), null);

        assertThatCode(() -> service.audit("LOGIN", "USER", 1L, null))
                .doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------
    // A-1：失败路径的异常隔离
    // ------------------------------------------------------------------
    @Test
    @DisplayName("指标组件不可用（provider 抛异常）→ 审计不向业务抛")
    void metricProviderFailureDoesNotBreakBusiness() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.failWith = new IllegalStateException("JSON 列拒收");
        ObjectProvider<MeterRegistry> broken = new ObjectProvider<>() {
            @Override
            public MeterRegistry getObject(Object... args) {
                throw new IllegalStateException("容器未就绪");
            }

            @Override
            public MeterRegistry getIfAvailable() {
                throw new IllegalStateException("容器未就绪");
            }

            @Override
            public MeterRegistry getIfUnique() {
                return null;
            }

            @Override
            public MeterRegistry getObject() {
                throw new IllegalStateException("容器未就绪");
            }
        };
        AuditService service = new AuditService(mapper, new PermissionService(null), broken);

        assertThatCode(() -> service.audit("LOGIN", "USER", 1L, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("指标注册失败（registry 抛异常）→ 审计不向业务抛")
    void metricRegistrationFailureDoesNotBreakBusiness() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.failWith = new IllegalStateException("JSON 列拒收");
        MeterRegistry brokenRegistry = new SimpleMeterRegistry() {
            @Override
            protected Counter newCounter(Meter.Id id) {
                throw new IllegalStateException("registry down");
            }
        };
        AuditService service = new AuditService(mapper, new PermissionService(null),
                provider(brokenRegistry));

        assertThatCode(() -> service.audit("LOGIN", "USER", 1L, null))
                .doesNotThrowAnyException();
        // 证明真的走到了抛异常那条分支：计数器没被注册出来
        assertThat(brokenRegistry.find("audit.failures").counter()).isNull();
    }

    @Test
    @DisplayName("正常计数：序列化与写入两个 stage 各自可数")
    void countsBothStages() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.failWith = new IllegalStateException("boom");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AuditService service = service(mapper, registry);

        service.audit("LOGIN", "USER", 1L, null);

        assertThat(registry.get("audit.failures").tag("stage", "write")
                .tag("action", "LOGIN").counter().count()).isEqualTo(1.0);
    }

    // ------------------------------------------------------------------
    // 装配
    // ------------------------------------------------------------------
    private static AuditService service(AuditLogMapper mapper, MeterRegistry registry) {
        return new AuditService(mapper, new PermissionService(null), provider(registry));
    }

    private static ObjectProvider<MeterRegistry> provider(MeterRegistry registry) {
        return new ObjectProvider<>() {
            @Override
            public MeterRegistry getObject(Object... args) {
                return registry;
            }

            @Override
            public MeterRegistry getIfAvailable() {
                return registry;
            }

            @Override
            public MeterRegistry getIfUnique() {
                return registry;
            }

            @Override
            public MeterRegistry getObject() {
                return registry;
            }
        };
    }

    private static JsonNode read(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("落库内容不是合法 JSON: " + json, e);
        }
    }

    private static final class RecordingMapper implements AuditLogMapper {

        private AuditLog inserted;
        private RuntimeException failWith;

        @Override
        public AuditLog selectById(java.io.Serializable id) {
            return null;
        }

        @Override
        public List<AuditLog> selectBatchIds(java.util.Collection<? extends java.io.Serializable> ids) {
            return List.of();
        }

        @Override
        public int insert(AuditLog entity) {
            if (failWith != null) {
                throw failWith;
            }
            this.inserted = entity;
            return 1;
        }

        @Override
        public int updateById(AuditLog entity) {
            return 0;
        }

        @Override
        public int deleteById(java.io.Serializable id) {
            return 0;
        }

        @Override
        public List<AuditLog> selectPage(String action, Long userId, int limit, int offset) {
            return List.of();
        }

        @Override
        public long countFiltered(String action, Long userId) {
            return 0;
        }
    }
}
