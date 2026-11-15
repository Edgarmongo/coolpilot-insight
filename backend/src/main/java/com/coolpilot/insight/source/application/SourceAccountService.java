package com.coolpilot.insight.source.application;

import com.coolpilot.insight.common.exception.BizException;
import com.coolpilot.insight.common.exception.ErrorCode;
import com.coolpilot.insight.source.domain.SourceAccount;
import com.coolpilot.insight.source.infrastructure.persistence.SourceAccountMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 平台账号管理。
 *
 * <p>三条硬规则：
 * 1. **auth_status 不可由管理接口写入** —— 只能由验证结果决定（见 updateProfile 不涉及该列）；
 * 2. **可用性判断统一走 {@link #isUsable}**，避免各处各写一套导致判定分叉；
 * 3. 凭证只存引用，任何出口都不返回凭证明文。
 */
@Service
public class SourceAccountService {

    /** 项目既有取值：ACTIVE / DISABLED（不是 ENABLED）。 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";
    public static final String AUTH_VALID = "VALID";
    public static final String AUTH_UNKNOWN = "UNKNOWN";

    private final SourceAccountMapper mapper;

    public SourceAccountService(SourceAccountMapper mapper) {
        this.mapper = mapper;
    }

    public List<SourceAccount> list() {
        return mapper.selectAll();
    }

    public SourceAccount detail(Long id) {
        SourceAccount a = mapper.selectById(id);
        if (a == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "账号不存在");
        }
        return a;
    }

    public SourceAccount create(SourceAccount in) {
        if (in.getSourceId() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "必须选择数据源");
        }
        if (isBlank(in.getAccountName())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "账号名称不能为空");
        }
        if (isBlank(in.getExternalAccountId())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "平台账号标识不能为空");
        }
        if (mapper.selectByExternal(in.getSourceId(), in.getExternalAccountId()) != null) {
            throw new BizException(ErrorCode.DUPLICATE, "该数据源下已存在同一账号标识");
        }
        // 新账号一律 UNKNOWN：未经验证不得假设授权有效
        in.setStatus(STATUS_ACTIVE);
        in.setAuthStatus(AUTH_UNKNOWN);
        mapper.insert(in);
        return mapper.selectById(in.getId());
    }

    public SourceAccount updateProfile(Long id, SourceAccount in) {
        SourceAccount exist = detail(id);
        exist.setAccountName(in.getAccountName());
        exist.setCredentialRef(in.getCredentialRef());
        exist.setExpiresAt(in.getExpiresAt());
        exist.setRemark(in.getRemark());
        mapper.updateProfile(exist);
        return mapper.selectById(id);
    }

    public void setStatus(Long id, String status) {
        if (!STATUS_ACTIVE.equals(status) && !STATUS_DISABLED.equals(status)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "状态只能是 ACTIVE 或 DISABLED");
        }
        detail(id);
        mapper.updateStatus(id, status);
    }

    /**
     * 授权有效性判定（唯一入口）。
     *
     * <p>{@code expiresAt == null} 表示无已知过期时间，**不算过期**，
     * 但仍要求 {@code authStatus == VALID}。
     */
    public boolean isUsable(SourceAccount a, Long taskSourceId) {
        if (a == null) {
            return false;
        }
        if (!STATUS_ACTIVE.equals(a.getStatus())) {
            return false;
        }
        if (!AUTH_VALID.equals(a.getAuthStatus())) {
            return false;
        }
        if (a.getExpiresAt() != null && a.getExpiresAt().isBefore(LocalDateTime.now())) {
            return false;
        }
        return taskSourceId == null || taskSourceId.equals(a.getSourceId());
    }

    /** 使用统计：调用次数取自 lastUsedAt，未实现配额与账号池。 */
    public Map<String, Object> usage(Long id) {
        SourceAccount a = detail(id);
        return Map.of(
                "accountId", a.getId(),
                "lastUsedAt", a.getLastUsedAt() == null ? "" : a.getLastUsedAt().toString(),
                "authStatus", a.getAuthStatus(),
                "status", a.getStatus());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
