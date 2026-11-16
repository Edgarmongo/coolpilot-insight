package com.coolpilot.insight.source;

import com.coolpilot.insight.AbstractIntegrationTest;
import com.coolpilot.insight.source.application.AccountVerificationService;
import com.coolpilot.insight.source.application.SourceAccountService;
import com.coolpilot.insight.source.domain.SourceAccount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 授权验证（A-02 收尾）。
 *
 * <p>断言重点是「不该给 VALID 的时候绝不给」：缺凭证、凭证文件不存在、已过期。
 * 采集服务不可达的场景不在此测——那依赖外部容器，会让测试随环境变红。
 */
class AccountVerificationTest extends AbstractIntegrationTest {

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
}
