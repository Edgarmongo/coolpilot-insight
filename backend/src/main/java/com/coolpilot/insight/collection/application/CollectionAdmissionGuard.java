package com.coolpilot.insight.collection.application;

import com.coolpilot.insight.common.exception.BizException;
import com.coolpilot.insight.common.exception.ErrorCode;
import com.coolpilot.insight.source.application.SourceQueryService;
import com.coolpilot.insight.source.domain.EffectiveCapability;
import com.coolpilot.insight.source.dto.DataSourceView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 采集执行前的准入校验（SOURCE-06）。
 *
 * <h3>为什么这是一个独立组件</h3>
 * 它要在**任何副作用发生之前**给出结论：任务不建、item 不插、HTTP 不发。
 * 放在编排方法里靠"记得先判断"，迟早会有人在重构时把顺序调换；
 * 抽成一个有名字的组件，调用点就只有一句，顺序也一目了然。
 *
 * <h3>为什么判定要复用 Effective</h3>
 * 采集是唯一真正会碰平台的动作。如果准入另写一套判据，
 * 它和页面/接口显示的 Effective 迟早会对不上——
 * 那时用户看到"不可执行"却还能点采集，三态模型就失效了。
 */
@Component
public class CollectionAdmissionGuard {

    private static final Logger log = LoggerFactory.getLogger(CollectionAdmissionGuard.class);

    private final SourceQueryService sourceQueryService;

    public CollectionAdmissionGuard(SourceQueryService sourceQueryService) {
        this.sourceQueryService = sourceQueryService;
    }

    /**
     * 校验某平台的某能力当前可执行。
     *
     * @throws BizException 409，附 {@code effectiveStatus/reason} 与服务端说明
     */
    public void requireExecutable(String sourceCode, String capabilityCode) {
        DataSourceView view = sourceQueryService.findByCode(sourceCode);
        requireExecutable(view, capabilityCode);
    }

    /**
     * 纯判定版本：输入就是一个视图，便于穷举测试。
     *
     * <p>拒绝时把 **status + reason + detail** 一起给出。
     * 只说"不可执行"会让运营无从下手：`UNKNOWN` 可能是服务挂了，
     * `UNREGISTERED` 可能是没声明，`ACCOUNT_REQUIRED` 可能是要登录账号。
     */
    public void requireExecutable(DataSourceView view, String capabilityCode) {
        if (view == null) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "数据源不存在，无法执行采集");
        }
        List<EffectiveCapability> effective = view.getEffective();
        if (effective == null || effective.isEmpty()) {
            String why = view.getDesiredError() != null
                    ? "业务声明无法解析：" + view.getDesiredError()
                    : "当前没有可用于核对的能力（Observed 快照状态 " + view.getSnapshotState() + "）";
            throw reject(capabilityCode, "UNKNOWN", "NO_EVIDENCE", why);
        }

        EffectiveCapability target = effective.stream()
                .filter(e -> capabilityCode.equals(e.code()))
                .findFirst()
                .orElse(null);
        if (target == null) {
            throw reject(capabilityCode, "UNREGISTERED", "NOT_DECLARED",
                    "该数据源没有声明能力 " + capabilityCode);
        }
        if (!target.executable()) {
            throw reject(capabilityCode, target.effectiveStatus(), target.effectiveReason(),
                    target.detail());
        }
        log.debug("[collection] 准入通过 source={} capability={}", view.getCode(), capabilityCode);
    }

    private BizException reject(String capabilityCode, String status, String reason, String detail) {
        String message = "当前不允许执行 " + capabilityCode + "：" + status + "/" + reason
                + (detail == null || detail.isBlank() ? "" : " —— " + detail);
        log.warn("[collection] 准入拒绝 {}", message);
        return new BizException(ErrorCode.STATE_CONFLICT, message);
    }
}
