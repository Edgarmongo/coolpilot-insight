package com.coolpilot.insight.source.application;

import com.coolpilot.insight.common.exception.BizException;
import com.coolpilot.insight.common.exception.ErrorCode;
import com.coolpilot.insight.integration.capability.RegistrySnapshot;
import com.coolpilot.insight.integration.capability.SnapshotCache;
import com.coolpilot.insight.source.domain.DataSource;
import com.coolpilot.insight.source.domain.DesiredCapabilitySet;
import com.coolpilot.insight.source.dto.SourceCapabilityDetailView;
import com.coolpilot.insight.source.infrastructure.persistence.DataSourceMapper;
import com.coolpilot.insight.system.application.AuditService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据源能力的读写用例（SOURCE-04-06）。
 *
 * <h3>写路径的边界</h3>
 * 只写 {@code ds_source.capability_json}，**不碰** {@code ds_account.auth_status}——
 * 后者由 {@link AccountVerificationService} 独占。两件事混写会造成
 * "改了能力配置导致账号掉线"这类无法解释的现象。
 *
 * <h3>为什么审计要写 before/after 全量</h3>
 * 只记"谁改了什么时间"无法回溯一次配置事故；只记 after 无法知道退回到哪一版。
 * 能力声明是小 JSON（几条到几十条），全量留存的成本远低于事后推测。
 */
@Service
public class SourceCapabilityService {

    private static final Logger log = LoggerFactory.getLogger(SourceCapabilityService.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 审计动作码；对外可见，便于审计检索与测试断言对齐同一常量。 */
    public static final String ACTION_UPDATE_DESIRED = "SOURCE_CAPABILITY_UPDATE";
    static final String RESOURCE_TYPE = "ds_source";

    private final DataSourceMapper dataSourceMapper;
    private final SnapshotCache snapshotCache;
    private final AccountAvailability accountAvailability;
    private final CapabilityReconciler reconciler;
    private final AuditService auditService;

    public SourceCapabilityService(DataSourceMapper dataSourceMapper,
                                   SnapshotCache snapshotCache,
                                   AccountAvailability accountAvailability,
                                   CapabilityReconciler reconciler,
                                   AuditService auditService) {
        this.dataSourceMapper = dataSourceMapper;
        this.snapshotCache = snapshotCache;
        this.accountAvailability = accountAvailability;
        this.reconciler = reconciler;
        this.auditService = auditService;
    }

    /**
     * 三态明细。
     *
     * <p>一次请求只取一份快照：Desired / Observed / Effective 必须来自同一时刻的 Registry，
     * 否则"三方对照"本身就不成立（SOURCE-04-02 §B）。
     */
    public SourceCapabilityDetailView detail(String code) {
        DataSource ds = require(code);
        return build(ds, snapshotCache.get());
    }

    /**
     * 覆盖写入 Desired。
     *
     * <p>校验不通过时**不写库、不审计**：失败请求没有改变任何状态，
     * 把它写进审计只会稀释审计的价值（真实的变更被噪声淹没）。
     *
     * @param rawBody 请求体原文（v2 文档）；解析与类型判定都在校验器里做
     */
    public SourceCapabilityDetailView replaceDesired(String code, String rawBody) {
        DataSource ds = require(code);

        DesiredWriteValidator.Result result =
                DesiredWriteValidator.validate(code, parseBody(rawBody));
        if (!result.ok()) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "能力声明校验失败：" + String.join("；", result.errors()));
        }

        String after = result.requireCanonicalJson();
        String before = ds.getCapabilityJson();

        // capability_json 是 JSON 列：非法 JSON 在这里会被 MySQL 拒绝，
        // 而 after 一定由校验后的模型序列化产生，不存在非法路径。
        if (dataSourceMapper.updateCapabilityJson(code, after) == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "数据源不存在: " + code);
        }
        log.info("[source] Desired 已更新 code={} capabilities={}",
                code, result.desired().capabilities().size());

        auditService.audit(ACTION_UPDATE_DESIRED, RESOURCE_TYPE, ds.getId(),
                auditDetail(before, after));

        // 重新读取：返回的是落库后的真实状态，不是请求体的回声
        return detail(code);
    }

    // ------------------------------------------------------------------
    private DataSource require(String code) {
        DataSource ds = dataSourceMapper.selectByCode(code);
        if (ds == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "数据源不存在: " + code);
        }
        return ds;
    }

    /**
     * 解析请求体。
     *
     * <p>非法 JSON 属于**参数错误**而不是服务故障：它必须得到 400，
     * 而不是让 {@code JsonProcessingException} 冒泡成 500——
     * 那会让调用方以为服务端坏了，从而重试一个永远不会成功的请求。
     */
    private JsonNode parseBody(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请求体不能为空");
        }
        try {
            return JSON.readTree(rawBody);
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "请求体不是合法 JSON: " + e.getMessage());
        }
    }

    private SourceCapabilityDetailView build(DataSource ds, SnapshotCache.SnapshotRead read) {
        DesiredCapabilitySet desired = DesiredCapabilityParser.parse(
                ds.getCapabilityJson(), ds.getCode());

        SourceCapabilityDetailView v = new SourceCapabilityDetailView();
        v.setSourceId(ds.getId());
        v.setSourceCode(ds.getCode());
        v.setSourceName(ds.getName());
        v.setSourceStatus(ds.getStatus());

        v.setDesiredSchemaVersion(desired.schemaVersion());
        v.setDesired(desired.capabilities());
        v.setDesiredError(desired.error());

        v.setSnapshotState(read.state().name());
        v.setSnapshotDetail(read.detail());
        if (read.snapshot() != null) {
            v.setSnapshotId(read.snapshot().snapshotId());
            v.setSnapshotGeneratedAt(read.snapshot().generatedAt());
            v.setObserved(observedOf(ds.getCode(), read.snapshot()));
        } else {
            // UNAVAILABLE 时不得携带快照（SnapshotRead 已保证），也不得臆造空观测
            v.setObserved(List.of());
        }

        v.setEffective(reconciler.reconcile(
                ds, desired, read, accountAvailability.hasUsableAccount(ds.getId())));
        return v;
    }

    /**
     * 该平台的真实接入。
     *
     * <p>过滤条件是 {@code platformScoped && sourceCode 相等}：
     * 前者排除链路验证替身（N7-1），后者把多平台快照收窄到本行关心的部分。
     * 不按 {@code connectorCode} 前缀猜——那会把命名习惯当成契约。
     */
    private List<SourceCapabilityDetailView.ObservedConnector> observedOf(
            String sourceCode, RegistrySnapshot snapshot) {
        List<SourceCapabilityDetailView.ObservedConnector> out = new ArrayList<>();
        for (RegistrySnapshot.AdapterView adapter : snapshot.platformScopedAdapters()) {
            if (adapter.sourceCode() == null || !adapter.sourceCode().equals(sourceCode)) {
                continue;
            }
            SourceCapabilityDetailView.ObservedConnector c =
                    new SourceCapabilityDetailView.ObservedConnector();
            c.setConnectorCode(adapter.connectorCode());
            c.setConnectorVersion(adapter.connectorVersion());
            c.setLoadStatus(adapter.loadStatus());
            c.setPlatformScoped(adapter.isPlatformScoped());
            c.setCapabilityCount(adapter.capabilityCount());
            c.setErrorCode(adapter.errorCode());
            c.setErrorMessage(adapter.errorMessage());
            c.setCapabilities(adapter.capabilities());
            c.setDiagnostics(diagnosticsOf(snapshot, adapter.connectorCode()));
            out.add(c);
        }
        return out;
    }

    private List<RegistrySnapshot.Diagnostic> diagnosticsOf(
            RegistrySnapshot snapshot, String connectorCode) {
        if (snapshot.diagnostics() == null) {
            return List.of();
        }
        return snapshot.diagnostics().stream()
                .filter(d -> connectorCode.equals(d.connectorCode()))
                .toList();
    }

    private String auditDetail(String before, String after) {
        ObjectNode root = JSON.createObjectNode();
        root.put("before", before);
        root.put("after", after);
        return root.toString();
    }
}
