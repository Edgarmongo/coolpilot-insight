package com.coolpilot.insight.source.application;

import com.coolpilot.insight.common.exception.BizException;
import com.coolpilot.insight.common.exception.ErrorCode;
import com.coolpilot.insight.integration.health.ServiceHealthProbe;
import com.coolpilot.insight.source.domain.SourceAccount;
import com.coolpilot.insight.source.infrastructure.credential.CredentialProvider;
import com.coolpilot.insight.source.infrastructure.persistence.SourceAccountMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 授权验证。**唯一可以写 auth_status 的地方。**
 *
 * <p>验证范围（必须如实告知调用方）：
 * <ul>
 *   <li>{@code CONFIG_ONLY} —— 本实现只能确认「凭证已配置 + 未过期 + 采集服务可达」，
 *       <b>无法确认 Cookie/Token 在平台上真的有效</b>。</li>
 * </ul>
 * 平台级真有效性只能由「带凭证的真实采集是否成功」证明，
 * 因此执行期需按采集失败原因把账号回写为 INVALID/EXPIRED。
 *
 * <p>不把"服务不可达"判成账号 INVALID —— 那是环境问题，不是账号问题，
 * 此时保持原状态并返回失败原因，避免误伤。
 *
 * <h3>服务健康必须分开判断</h3>
 * <p>本类探测<b>两个互不相同的服务</b>，二者不能相互替代：
 * <ul>
 *   <li>{@code COLLECTION_SERVICE} —— 采集服务。账号能否"可用"依赖于它，
 *       不可达时返回 {@code SERVICE_UNAVAILABLE}，<b>不改 auth_status</b>。</li>
 *   <li>{@code AI_SERVICE} —— 分词 / AI 分析服务。与账号授权无关，
 *       不可达只如实报告，<b>不影响账号状态判定</b>。</li>
 * </ul>
 * <p>历史缺陷：本类曾把 ai-service 的 {@code /health} 当成采集服务的可达性，
 * 二者都返回 200 于是长期未暴露。修复方式是把"探测谁"变成显式参数
 * （{@link ServiceHealthProbe}），而不是靠 URL 恰好不同来区分。
 */
@Service
public class AccountVerificationService {

    private static final Logger log = LoggerFactory.getLogger(AccountVerificationService.class);

    /** 本实现的能力边界，随验证结果一起返回给前端。 */
    public static final String SCOPE_CONFIG_ONLY = "CONFIG_ONLY";

    /** 采集服务标识。 */
    public static final String SERVICE_COLLECTION = "COLLECTION_SERVICE";
    /** AI（分词 / 分析）服务标识。 */
    public static final String SERVICE_AI = "AI_SERVICE";

    private final SourceAccountMapper mapper;
    private final CredentialProvider credentialProvider;
    private final ServiceHealthProbe collectionProbe;
    private final ServiceHealthProbe aiProbe;

    public AccountVerificationService(
            SourceAccountMapper mapper,
            CredentialProvider credentialProvider,
            @Value("${app.collection-service-url:http://127.0.0.1:5001}") String collectionServiceUrl,
            @Value("${app.ai-service-url:http://127.0.0.1:5000}") String aiServiceUrl) {
        this.mapper = mapper;
        this.credentialProvider = credentialProvider;
        this.collectionProbe = new ServiceHealthProbe(SERVICE_COLLECTION, collectionServiceUrl);
        this.aiProbe = new ServiceHealthProbe(SERVICE_AI, aiServiceUrl);
    }

    public VerifyResult verify(Long accountId) {
        SourceAccount a = mapper.selectById(accountId);
        if (a == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "账号不存在");
        }

        // 0) 凭证存储本身不可用 → 部署异常，保持原状态，不要把账号批量判成 INVALID
        if (!credentialProvider.isStorageAvailable()) {
            log.warn("[verify] 凭证目录不可用，账号 {} 状态保持不变", accountId);
            return new VerifyResult(accountId, a.getAuthStatus(), false,
                    "SECRET_STORAGE_UNAVAILABLE",
                    "凭证存储目录不可用（未挂载或不可读），未改变账号状态",
                    SCOPE_CONFIG_ONLY, Map.of());
        }

        // 1) 过期优先：这是时间上的确定事实，不该被"凭证缺失"掩盖
        if (a.getExpiresAt() != null && a.getExpiresAt().isBefore(LocalDateTime.now())) {
            return fail(a, "EXPIRED", "CREDENTIAL_EXPIRED",
                    "授权已于 " + a.getExpiresAt() + " 过期", Map.of());
        }

        // 2) 凭证是否配置
        if (isBlank(a.getCredentialRef())) {
            return fail(a, "INVALID", "CREDENTIAL_MISSING", "未配置凭证引用", Map.of());
        }
        if (!credentialProvider.isConfigured(a.getCredentialRef())) {
            return fail(a, "INVALID", "CREDENTIAL_NOT_FOUND",
                    "凭证引用存在但不指向可读的凭证文件", Map.of());
        }

        // 3) 两个服务分别探活 —— 结果各自独立，互不替代
        ServiceHealthProbe.Result collection = collectionProbe.probe();
        ServiceHealthProbe.Result ai = aiProbe.probe();
        Map<String, ServiceCheck> checks = new LinkedHashMap<>();
        checks.put(SERVICE_COLLECTION, toCheck(collection));
        checks.put(SERVICE_AI, toCheck(ai));

        // 3a) 采集服务不可达 → 环境问题，保持原状态（AI 服务是否可达不影响本判定）
        if (!collection.reachable()) {
            log.warn("[verify] 采集服务不可达（{}），账号 {} 状态保持不变",
                    collection.detail(), accountId);
            return new VerifyResult(accountId, a.getAuthStatus(), false,
                    "SERVICE_UNAVAILABLE",
                    "采集服务不可达，未改变账号状态",
                    SCOPE_CONFIG_ONLY, checks);
        }

        // 4) 全部通过 → VALID（配置层面有效）
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime authorizedAt = a.getAuthorizedAt() == null ? now : a.getAuthorizedAt();
        mapper.updateAuthResult(accountId, "VALID", now, authorizedAt, null, null);
        String note = ai.reachable()
                ? "凭证已配置且未过期（平台级有效性需真实采集验证）"
                : "凭证已配置且未过期；AI 服务当前不可达，不影响账号授权判定"
                + "（平台级有效性需真实采集验证）";
        return new VerifyResult(accountId, "VALID", true, null, note,
                SCOPE_CONFIG_ONLY, checks);
    }

    private VerifyResult fail(SourceAccount a, String status, String code, String message,
                              Map<String, ServiceCheck> checks) {
        mapper.updateAuthResult(a.getId(), status, LocalDateTime.now(), a.getAuthorizedAt(),
                code, message);
        return new VerifyResult(a.getId(), status, false, code, message,
                SCOPE_CONFIG_ONLY, checks);
    }

    private static ServiceCheck toCheck(ServiceHealthProbe.Result r) {
        return new ServiceCheck(r.serviceName(), r.baseUrl(), r.reachable(), r.detail());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 单个服务的可达性结论。
     *
     * @param serviceName 服务标识
     * @param url 被探测的地址
     * @param reachable 是否可达
     * @param detail 细节
     */
    public record ServiceCheck(String serviceName, String url, boolean reachable, String detail) {
    }

    /**
     * 验证结果。
     *
     * @param scope 验证能力边界（当前固定 {@code CONFIG_ONLY}）
     * @param serviceChecks 两个服务的**独立**可达性结论
     */
    public record VerifyResult(Long accountId, String authStatus, boolean success,
                               String errorCode, String message, String scope,
                               Map<String, ServiceCheck> serviceChecks) {
    }
}
