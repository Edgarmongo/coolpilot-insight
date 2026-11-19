package com.coolpilot.insight.source;

import com.coolpilot.insight.AbstractIntegrationTest;
import com.coolpilot.insight.common.exception.BizException;
import com.coolpilot.insight.source.application.SourceAccountService;
import com.coolpilot.insight.source.domain.SourceAccount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 平台账号（A-02）：双状态与可用性判定。 */
class SourceAccountTest extends AbstractIntegrationTest {

    @Autowired private SourceAccountService service;

    private SourceAccount seed(String ext) {
        SourceAccount a = new SourceAccount();
        a.setSourceId(1L);
        a.setAccountName("测试账号" + ext);
        a.setExternalAccountId(ext);
        a.setAuthType("COOKIE");
        a.setCredentialRef("local-secret:account/" + ext);
        return service.create(a);
    }

    @Test
    @DisplayName("新账号为 ACTIVE + UNKNOWN，不可用")
    void newAccountIsUnknown() {
        SourceAccount a = seed("acc-1");
        assertThat(a.getStatus()).isEqualTo("ACTIVE");
        assertThat(a.getAuthStatus()).isEqualTo("UNKNOWN");
        assertThat(service.isUsable(a, 1L)).isFalse();
    }

    @Test
    @DisplayName("只有 VALID 且未过期才可用；expiresAt 为空不算过期")
    void usableRules() {
        SourceAccount a = seed("acc-2");
        a.setAuthStatus("VALID");

        assertThat(service.isUsable(a, 1L)).as("无过期时间应可用").isTrue();
        assertThat(service.isUsable(a, 2L)).as("Source 不匹配不可用").isFalse();

        a.setExpiresAt(LocalDateTime.now().minusDays(1));
        assertThat(service.isUsable(a, 1L)).as("已过期不可用").isFalse();

        a.setExpiresAt(null);
        a.setStatus("DISABLED");
        assertThat(service.isUsable(a, 1L)).as("停用不可用").isFalse();
    }

    @Test
    @DisplayName("同源重复账号标识被拒")
    void duplicateRejected() {
        seed("acc-3");
        assertThatThrownBy(() -> seed("acc-3"))
                .isInstanceOf(BizException.class);
    }
}
