package com.coolpilot.insight.integration.capability;

import java.util.List;

/**
 * Python 侧 {@code GET /v1/capabilities} 的解析结果（Observed 视图）。
 *
 * @param schemaVersion   顶层 Schema 版本原文
 * @param schemaMajor     主版本号
 * @param schemaSupported 是否认识这个主版本
 * @param documents       平台能力文档（主版本不认识时为空）
 * @param warnings        解析告警（例如主版本不支持）
 */
public record ObservedCapabilities(
        String schemaVersion,
        int schemaMajor,
        boolean schemaSupported,
        List<CapabilityDocument> documents,
        List<String> warnings) {

    public ObservedCapabilities {
        documents = documents == null ? List.of() : List.copyOf(documents);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /**
     * 是否处于"未知"状态。
     *
     * <p>SOURCE-02 §02-06 的硬规则：Java 遇到不认识的 {@code schemaVersion} 主版本
     * → 拒绝解析并标记 {@code UNKNOWN}，**不静默忽略**、更不能伪造为不可用。
     */
    public boolean isUnknown() {
        return !schemaSupported;
    }

    /** 按接入实现编码查找文档。 */
    public CapabilityDocument findByConnector(String connectorCode) {
        for (CapabilityDocument doc : documents) {
            if (doc.connectorCode().equals(connectorCode)) {
                return doc;
            }
        }
        return null;
    }

    /** 按平台编码查找文档（一个平台可能有多条接入实现）。 */
    public List<CapabilityDocument> findBySource(String sourceCode) {
        return documents.stream()
                .filter(d -> d.sourceCode().equals(sourceCode))
                .toList();
    }
}
