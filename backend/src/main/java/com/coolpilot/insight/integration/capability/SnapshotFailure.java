package com.coolpilot.insight.integration.capability;

/**
 * 一次快照刷新失败的结构化描述。
 *
 * <p>为什么不用裸字符串：核对层要把失败映射成不同的 {@code effectiveReason}
 * （{@code REGISTRY_UNREACHABLE} vs {@code SCHEMA_UNSUPPORTED}），
 * 靠解析错误文本判断违反"机器只看 code"的原则（SOURCE-03 03-05）。
 *
 * @param kind   失败分类，来自 {@code CollectionServiceClient.FetchResult.Kind}
 * @param detail 人类可读说明，只进日志与展示
 */
public record SnapshotFailure(CollectionServiceClient.FetchResult.Kind kind, String detail) {

    /** 服务层不可达（连接失败 / 超时 / 非 200）。 */
    public boolean isUnreachable() {
        return kind == CollectionServiceClient.FetchResult.Kind.UNREACHABLE
                || kind == CollectionServiceClient.FetchResult.Kind.HTTP_ERROR;
    }

    /** 响应拿到了但不可用（结构 / 版本 / 自洽性问题）。 */
    public boolean isProtocolProblem() {
        return kind == CollectionServiceClient.FetchResult.Kind.MALFORMED_RESPONSE
                || kind == CollectionServiceClient.FetchResult.Kind.SCHEMA_UNSUPPORTED
                || kind == CollectionServiceClient.FetchResult.Kind.CONTRACT_UNSUPPORTED
                || kind == CollectionServiceClient.FetchResult.Kind.INCONSISTENT;
    }

    public static SnapshotFailure of(CollectionServiceClient.FetchResult.Kind kind, String detail) {
        return new SnapshotFailure(kind, detail);
    }
}
