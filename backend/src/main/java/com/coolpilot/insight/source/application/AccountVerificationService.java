package com.coolpilot.insight.source.application;

import com.coolpilot.insight.common.exception.BizException;
import com.coolpilot.insight.common.exception.ErrorCode;
import com.coolpilot.insight.source.domain.SourceAccount;
import com.coolpilot.insight.source.infrastructure.credential.CredentialProvider;
import com.coolpilot.insight.source.infrastructure.persistence.SourceAccountMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;

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
 */
@Service
public class AccountVerificationService {

    private static final Logger log = LoggerFactory.getLogger(AccountVerificationService.class);

    /** 本实现的能力边界，随验证结果一起返回给前端。 */
    public static final String SCOPE_CONFIG_ONLY = "CONFIG_ONLY";

    private final SourceAccountMapper mapper;
    private final CredentialProvider credentialProvider;
    private final String collectionBaseUrl;

    public AccountVerificationService(SourceAccountMapper mapper,
                                      CredentialProvider credentialProvider,
                                      @Value("${app.collection-service-url:http://cpi-flask:5000}") String collectionBaseUrl) {
        this.mapper = mapper;
        this.credentialProvider = credentialProvider;
        this.collectionBaseUrl = collectionBaseUrl;
    }

    public VerifyResult verify(Long accountId) {
        SourceAccount a = mapper.selectById(accountId);
        if (a == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "账号不存在");
        }

        // 1) 过期优先：这是时间上的确定事实，不该被"凭证缺失"掩盖
        if (a.getExpiresAt() != null && a.getExpiresAt().isBefore(LocalDateTime.now())) {
            return fail(a, "EXPIRED", "CREDENTIAL_EXPIRED", "授权已于 " + a.getExpiresAt() + " 过期");
        }

        // 2) 凭证是否配置
        if (isBlank(a.getCredentialRef())) {
            return fail(a, "INVALID", "CREDENTIAL_MISSING", "未配置凭证引用");
        }
        if (!credentialProvider.isConfigured(a.getCredentialRef())) {
            return fail(a, "INVALID", "CREDENTIAL_NOT_FOUND",
                    "凭证引用存在但不指向可读的凭证文件");
        }

        // 3) 采集服务是否可达（不可达不算账号问题，保持原状态）
        if (!collectionReachable()) {
            log.warn("[verify] 采集服务不可达，账号 {} 状态保持不变", accountId);
            return new VerifyResult(accountId, a.getAuthStatus(), false,
                    "SERVICE_UNAVAILABLE", "采集服务不可达，未改变账号状态", SCOPE_CONFIG_ONLY);
        }

        // 4) 全部通过 → VALID（配置层面有效）
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime authorizedAt = a.getAuthorizedAt() == null ? now : a.getAuthorizedAt();
        mapper.updateAuthResult(accountId, "VALID", now, authorizedAt, null, null);
        return new VerifyResult(accountId, "VALID", true, null,
                "凭证已配置且未过期（平台级有效性需真实采集验证）", SCOPE_CONFIG_ONLY);
    }

    private VerifyResult fail(SourceAccount a, String status, String code, String message) {
        mapper.updateAuthResult(a.getId(), status, LocalDateTime.now(), a.getAuthorizedAt(),
                code, message);
        return new VerifyResult(a.getId(), status, false, code, message, SCOPE_CONFIG_ONLY);
    }

    private boolean collectionReachable() {
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build()) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(collectionBaseUrl + "/health"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 验证结果：带上 scope，让调用方知道这次验证能证明到什么程度。 */
    public record VerifyResult(Long accountId, String authStatus, boolean success,
                               String errorCode, String message, String scope) {
    }
}
