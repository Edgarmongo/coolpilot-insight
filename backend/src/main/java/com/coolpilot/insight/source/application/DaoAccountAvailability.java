package com.coolpilot.insight.source.application;

import com.coolpilot.insight.source.domain.SourceAccount;
import com.coolpilot.insight.source.infrastructure.persistence.SourceAccountMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 基于 {@code ds_account} 的账号可用性实现。
 *
 * <p>判定条件（三者同时满足）：{@code status=ACTIVE} + {@code auth_status=VALID}
 * + 未过期。任一条不满足都算"没有可用账号"——因为对执行准入而言，
 * "管理员停用"和"授权过期"的结论是一样的：不能用。
 */
@Component
public class DaoAccountAvailability implements AccountAvailability {

    private final SourceAccountMapper accountMapper;

    public DaoAccountAvailability(SourceAccountMapper accountMapper) {
        this.accountMapper = accountMapper;
    }

    @Override
    public boolean hasUsableAccount(Long sourceId) {
        if (sourceId == null) {
            return false;
        }
        List<SourceAccount> accounts = accountMapper.selectBySourceId(sourceId);
        if (accounts == null || accounts.isEmpty()) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        for (SourceAccount a : accounts) {
            if (!"ACTIVE".equals(a.getStatus())) {
                continue;
            }
            if (!"VALID".equals(a.getAuthStatus())) {
                continue;
            }
            if (a.getExpiresAt() != null && !a.getExpiresAt().isAfter(now)) {
                continue;
            }
            return true;
        }
        return false;
    }
}
