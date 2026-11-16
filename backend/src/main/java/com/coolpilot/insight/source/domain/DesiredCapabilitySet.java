package com.coolpilot.insight.source.domain;

import java.util.List;
import java.util.Optional;

/**
 * 一个 Source 的 Desired 能力集合，是 {@code capability_json} 解析后的内存视图。
 *
 * <p>解析可能失败（非法 JSON / 未知主版本 / 无法确定 connectorCode）。
 * 失败时 {@link #supported()} 为 {@code false}，**不抛异常**——
 * 核对层需要把"Desired 读不懂"表达成一个 Effective 结论，
 * 而不是让整个列表接口 500。
 *
 * @param capabilities  解析出的能力声明
 * @param schemaVersion 结构版本；v1 无此字段时为 {@code "1"}
 * @param supported     是否可正常核对
 * @param error         不可核对时的原因（人类可读）
 */
public record DesiredCapabilitySet(
        List<DesiredCapability> capabilities,
        String schemaVersion,
        boolean supported,
        String error) {

    /** 解析失败。 */
    public static DesiredCapabilitySet unsupported(String error) {
        return new DesiredCapabilitySet(List.of(), null, false, error);
    }

    /** 成功。 */
    public static DesiredCapabilitySet of(List<DesiredCapability> capabilities, String schemaVersion) {
        return new DesiredCapabilitySet(List.copyOf(capabilities), schemaVersion, true, null);
    }

    public static DesiredCapabilitySet empty() {
        return of(List.of(), "1");
    }

    /**
     * 查该能力声明。
     *
     * <p>返回 {@code DISABLED} 的条目也算“找到”——调用方用
     * {@link DesiredCapability#enabled()} 区分。这样诊断信息能说明
     * “业务主动禁用了它”，而不是笼统的“没声明”。
     */
    public Optional<DesiredCapability> find(String connectorCode, String code) {
        return capabilities.stream()
                .filter(c -> eq(c.connectorCode(), connectorCode) && eq(c.code(), code))
                .findFirst();
    }

    /** 是否**有效声明**（存在且 ENABLED）。 */
    public boolean declares(String connectorCode, String code) {
        return find(connectorCode, code).map(DesiredCapability::enabled).orElse(false);
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
