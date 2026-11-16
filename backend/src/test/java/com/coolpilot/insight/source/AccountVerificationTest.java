package com.coolpilot.insight.source;

import com.coolpilot.insight.AbstractIntegrationTest;
import com.coolpilot.insight.source.application.AccountVerificationService;
import com.coolpilot.insight.source.application.SourceAccountService;
import com.coolpilot.insight.source.domain.SourceAccount;
import com.coolpilot.insight.source.infrastructure.credential.LocalSecretCredentialProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 授权验证。
 *
 * <p>断言重点是「不该给 VALID 的时候绝不给」，并区分两类失败：
 * 账号自身配置问题（INVALID/EXPIRED）vs 部署环境问题（SECRET_STORAGE_UNAVAILABLE）。
 */
@TestPropertySource(properties = "app.secret-dir=target/test-secrets")
class AccountVerificationTest extends AbstractIntegrationTest {

    // 必须在 Spring 上下文启动前建好目录：Provider 构造时就确定存储路径，
    // 目录不存在会先命中 SECRET_STORAGE_UNAVAILABLE，测不到凭证级分支。
    static {
        try {
            java.nio.file.Files.createDirectories(
                    java.nio.file.Paths.get("target/test-secrets/account"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("无法创建测试凭证目录", e);
        }
    }

    @Autowired private SourceAccountService accountService;
    @Autowired private AccountVerificationService verificationService;

    private SourceAccount seed(String ext, String credRef, LocalDateTime expiresAt) {
        SourceAccount a = new SourceAccount();
        a.setSourceId(1L);
        a.setAccountName("验证测试" + ext);
        a.setExternalAccountId(ext);
        a.setAuthType("COOKIE");
        a.setCredentialRef(credRef);
        a.setExpiresAt(expiresAt);
        return accountService.create(a);
    }

    @Test
    @DisplayName("未配置凭证引用 → INVALID/CREDENTIAL_MISSING")
    void missingRef() {
        SourceAccount a = seed("v-1", null, null);

        var r = verificationService.verify(a.getId());

        assertThat(r.authStatus()).isEqualTo("INVALID");
        assertThat(r.errorCode()).isEqualTo("CREDENTIAL_MISSING");
        assertThat(accountService.detail(a.getId()).getAuthStatus()).isEqualTo("INVALID");
    }

    @Test
    @DisplayName("引用存在但文件不可读 → INVALID/CREDENTIAL_NOT_FOUND")
    void refNotResolvable() {
        SourceAccount a = seed("v-2", "local-secret:account/not-exist", null);

        var r = verificationService.verify(a.getId());

        assertThat(r.authStatus()).isEqualTo("INVALID");
        assertThat(r.errorCode()).isEqualTo("CREDENTIAL_NOT_FOUND");
    }

    @Test
    @DisplayName("已过期优先于其它判断 → EXPIRED")
    void expired() {
        SourceAccount a = seed("v-3", null, LocalDateTime.now().minusDays(1));

        var r = verificationService.verify(a.getId());

        assertThat(r.authStatus()).isEqualTo("EXPIRED");
        assertThat(r.errorCode()).isEqualTo("CREDENTIAL_EXPIRED");
        assertThat(r.scope()).isEqualTo("CONFIG_ONLY");
    }

    @Test
    @DisplayName("验证结果必须带 scope，声明只能证明配置层面")
    void alwaysReportsScope() {
        SourceAccount a = seed("v-4", null, null);
        assertThat(verificationService.verify(a.getId()).scope()).isEqualTo("CONFIG_ONLY");
    }

    @Test
    @DisplayName("凭证目录不存在 → 存储不可用，不能据此判账号无效")
    void storageUnavailable() {
        var missing = new LocalSecretCredentialProvider("target/no-such-secret-dir");
        assertThat(missing.isStorageAvailable())
                .as("目录不存在应判为存储不可用")
                .isFalse();

        var present = new LocalSecretCredentialProvider("target/test-secrets");
        assertThat(present.isStorageAvailable()).isTrue();
    }

    @Test
    @DisplayName("目录存在但文件缺失 → CREDENTIAL_NOT_FOUND（与存储异常区分）")
    void storageOkButFileMissing() {
        SourceAccount a = seed("v-5", "local-secret:account/absent", null);
        var r = verificationService.verify(a.getId());
        assertThat(r.errorCode()).isEqualTo("CREDENTIAL_NOT_FOUND");
    }
}
