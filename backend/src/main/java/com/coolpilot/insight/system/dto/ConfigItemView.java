package com.coolpilot.insight.system.dto;

import java.time.LocalDateTime;

/**
 * 配置项视图。
 *
 * <p>`isSecret=1` 的项（如 API Key）**永不返回明文**，只返回是否已配置。
 */
public class ConfigItemView {

    private String key;
    private String value;
    private String valueType;
    private boolean secret;
    /** 密钥类配置：是否已有值（不暴露内容） */
    private boolean configured;
    private String description;
    private LocalDateTime updatedAt;

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getValueType() {
        return valueType;
    }

    public void setValueType(String valueType) {
        this.valueType = valueType;
    }

    public boolean isSecret() {
        return secret;
    }

    public void setSecret(boolean secret) {
        this.secret = secret;
    }

    public boolean isConfigured() {
        return configured;
    }

    public void setConfigured(boolean configured) {
        this.configured = configured;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
