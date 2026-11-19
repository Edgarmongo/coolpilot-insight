package com.coolpilot.insight.source.application;

import com.coolpilot.insight.common.exception.BizException;
import com.coolpilot.insight.common.exception.ErrorCode;
import com.coolpilot.insight.integration.capability.SnapshotCache;
import com.coolpilot.insight.source.domain.DataSource;
import com.coolpilot.insight.source.domain.DesiredCapabilitySet;
import com.coolpilot.insight.source.domain.DesiredSchema;
import com.coolpilot.insight.source.dto.DataSourceView;
import com.coolpilot.insight.source.infrastructure.persistence.DataSourceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据源查询与能力状态。
 *
 * <p>能力状态存在 {@code capability_json} 而不是写死在代码里：
 * 新增平台或某能力从"未验证"变为"可用"时，改数据即可，不用发版。
 */
@Service
public class SourceQueryService {

    /**
     * 局部实例，不依赖 Spring 容器。
     *
     * <p>Spring Boot 4 把 Jackson 自动配置拆成独立模块，容器里不一定有 ObjectMapper bean；
     * 这里只解析一小段配置 JSON，没必要为此引入全局 bean。
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DataSourceMapper dataSourceMapper;
    private final SnapshotCache snapshotCache;
    private final AccountAvailability accountAvailability;
    private final CapabilityReconciler reconciler;

    public SourceQueryService(DataSourceMapper dataSourceMapper,
                              SnapshotCache snapshotCache,
                              AccountAvailability accountAvailability,
                              CapabilityReconciler reconciler) {
        this.dataSourceMapper = dataSourceMapper;
        this.snapshotCache = snapshotCache;
        this.accountAvailability = accountAvailability;
        this.reconciler = reconciler;
    }

    /**
     * 列表。
     *
     * <p>**一次请求只取一份 Observed 快照**，所有数据源共用——
     * 否则同一屏里不同行可能来自不同版本的 Registry，形成混合快照
     * （SOURCE-04-02 §B 的硬约束）。
     */
    public List<DataSourceView> list() {
        SnapshotCache.SnapshotRead read = snapshotCache.get();
        return dataSourceMapper.selectAll().stream()
                .map(ds -> toView(ds, read))
                .toList();
    }

    public DataSourceView findByCode(String code) {
        DataSource ds = dataSourceMapper.selectByCode(code);
        if (ds == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "数据源不存在: " + code);
        }
        return toView(ds, snapshotCache.get());
    }

    public void updateStatus(String code, String status) {
        if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "状态只能是 ENABLED 或 DISABLED");
        }
        if (dataSourceMapper.updateStatus(code, status) == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "数据源不存在: " + code);
        }
    }

    private DataSourceView toView(DataSource ds, SnapshotCache.SnapshotRead read) {
        DataSourceView v = new DataSourceView();
        v.setId(ds.getId());
        v.setCode(ds.getCode());
        v.setName(ds.getName());
        v.setSourceType(ds.getSourceType());
        v.setAdapterCode(ds.getAdapterCode());
        v.setStatus(ds.getStatus());
        v.setRemark(ds.getRemark());

        // Desired：业务声明（v1/v2 双读）
        DesiredCapabilitySet desired = DesiredCapabilityParser.parse(
                ds.getCapabilityJson(), ds.getCode());
        v.setDesired(desired.capabilities());
        v.setDesiredError(desired.error());

        // 兼容期：旧结构字段只读，v2 行由 Desired 派生（不再原样回显 v2 文档）
        v.setCapability(legacyCapability(ds.getCapabilityJson(), desired));

        // Observed 新鲜度：必须如实暴露，不能把 STALE 当正常
        v.setSnapshotState(read.state().name());
        v.setSnapshotDetail(read.detail());

        // Effective：三态核对（派生结论，不落库）
        v.setEffective(reconciler.reconcile(
                ds, desired, read, accountAvailability.hasUsableAccount(ds.getId())));
        return v;
    }

    /**
     * 旧结构（{@code comments} 等）的**派生输出**（SOURCE-04-04 §2.7 · E3）。
     *
     * <p>v2 行不再原样回显：{@code capability_json} 已经是 v2 文档，
     * 若把整份文档塞进 {@code capability} 字段，老前端读到的是
     * {@code {schemaVersion, capabilities}} 而不是它认识的三个布尔——
     * 这不是兼容，是形状变了但没告诉调用方。
     *
     * <p>v1 行原样返回：迁移未覆盖的行仍有真实的旧结构，改写它等于伪造。
     */
    private Map<String, Object> legacyCapability(String rawJson, DesiredCapabilitySet desired) {
        if (DesiredCapabilityParser.majorOf(desired.schemaVersion())
                < DesiredSchema.SUPPORTED_MAJOR) {
            return parseCapability(rawJson);
        }
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("comments", enabled(desired, "COMMENT"));
        legacy.put("contentDetail", enabled(desired, "CONTENT_DETAIL"));
        legacy.put("keywordSearch", enabled(desired, "KEYWORD_SEARCH"));
        return legacy;
    }

    /** 任一 Connector 下声明为 ENABLED 即视为提供（旧的布尔字段是跨接入的粗粒度表达）。 */
    private static boolean enabled(DesiredCapabilitySet desired, String code) {
        return desired.capabilities().stream()
                .anyMatch(c -> code.equals(c.code()) && c.enabled());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseCapability(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, LinkedHashMap.class);
        } catch (Exception e) {
            // 能力 JSON 解析失败不应导致整个列表 500——降级为空能力，由调用方按"不可用"处理
            return Map.of();
        }
    }
}
