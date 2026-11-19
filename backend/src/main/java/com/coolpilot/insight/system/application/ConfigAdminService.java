package com.coolpilot.insight.system.application;

import com.coolpilot.insight.system.domain.SystemConfig;
import com.coolpilot.insight.system.dto.ConfigItemView;
import com.coolpilot.insight.system.infrastructure.persistence.SystemConfigMapper;
import com.coolpilot.insight.common.exception.BizException;
import com.coolpilot.insight.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 配置管理用例（管理员视角）。
 *
 * <p>两条硬规则：
 * 1. **密钥类配置（is_secret=1）永不回显明文**，只返回"是否已配置"；
 * 2. 更新后必须让 {@link ConfigService} 的本地缓存失效，否则最长 60 秒内读到旧值。
 */
@Service
public class ConfigAdminService {

    private final SystemConfigMapper configMapper;
    private final ConfigService configService;
    private final PermissionService permissionService;
    private final AuditService auditService;

    public ConfigAdminService(SystemConfigMapper configMapper,
                              ConfigService configService,
                              PermissionService permissionService,
                              AuditService auditService) {
        this.configMapper = configMapper;
        this.configService = configService;
        this.permissionService = permissionService;
        this.auditService = auditService;
    }

    public List<ConfigItemView> list() {
        return configMapper.selectAllConfig().stream().map(this::toView).toList();
    }

    /**
     * 更新配置值。
     *
     * @throws IllegalArgumentException 键不存在或值不符合 valueType
     */
    @Transactional
    public ConfigItemView update(String key, String value) {
        SystemConfig existing = configMapper.selectByKey(key);
        if (existing == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "配置项不存在: " + key);
        }
        validateValue(existing.getValueType(), value);

        configMapper.updateValue(key, value, permissionService.currentUserId());
        configService.invalidate();

        auditService.audit("CONFIG_UPDATE", "SYSTEM_CONFIG", existing.getId(),
                "{\"key\":\"" + key + "\"}");

        return toView(configMapper.selectByKey(key));
    }

    private void validateValue(String valueType, String value) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "配置值不能为空");
        }
        String type = valueType == null ? "STRING" : valueType.toUpperCase();
        try {
            switch (type) {
                case "INT", "INTEGER" -> Integer.parseInt(value.trim());
                case "DECIMAL", "NUMBER" -> new java.math.BigDecimal(value.trim());
                case "BOOLEAN" -> {
                    String v = value.trim().toLowerCase();
                    if (!v.equals("true") && !v.equals("false")) {
                        throw new BizException(ErrorCode.PARAM_INVALID, "布尔配置只接受 true/false");
                    }
                }
                default -> { /* STRING 不校验 */ }
            }
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "配置值类型应为 " + type + "，实际为: " + value);
        }
    }

    private ConfigItemView toView(SystemConfig c) {
        ConfigItemView v = new ConfigItemView();
        v.setKey(c.getConfigKey());
        v.setValueType(c.getValueType());
        v.setDescription(c.getDescription());
        v.setUpdatedAt(c.getUpdatedAt());

        boolean secret = c.getIsSecret() != null && c.getIsSecret() == 1;
        v.setSecret(secret);
        if (secret) {
            // 密钥不回显：只告知是否已配置
            v.setConfigured(c.getConfigValue() != null && !c.getConfigValue().isBlank());
            v.setValue(null);
        } else {
            v.setValue(c.getConfigValue());
            v.setConfigured(true);
        }
        return v;
    }
}
