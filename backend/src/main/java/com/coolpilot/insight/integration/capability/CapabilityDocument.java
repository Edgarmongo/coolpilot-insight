package com.coolpilot.insight.integration.capability;

import java.util.List;

/**
 * 一个接入实现（Connector）的 Observed 能力文档。
 *
 * @param schemaVersion    顶层 Schema 版本，当前固定 "1.0"
 * @param sourceCode       平台业务标识（DOUYIN / XIAOHONGSHU / KUAISHOU / WEB）
 * @param connectorCode    接入实现标识，如 DOUYIN_PUBLIC
 * @param connectorVersion 接入实现版本（semver）
 * @param capabilities     Observed 能力清单（函数能解析的才会出现在这里）
 */
public record CapabilityDocument(
        String schemaVersion,
        String sourceCode,
        String connectorCode,
        String connectorVersion,
        List<CapabilityItem> capabilities) {

    public CapabilityDocument {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }

    /** 按能力码查找。找不到返回 {@code null}。 */
    public CapabilityItem find(String capabilityCode) {
        for (CapabilityItem item : capabilities) {
            if (item.code().equals(capabilityCode)) {
                return item;
            }
        }
        return null;
    }
}
