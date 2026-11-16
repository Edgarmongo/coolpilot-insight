package com.coolpilot.insight.integration.capability;

/**
 * 快照来源。抽出接口的唯一目的是让缓存的状态机能被独立测试——
 * 不需要起 HTTP 服务，也不需要 mock 框架，传一个 lambda 即可。
 */
@FunctionalInterface
public interface SnapshotProvider {

    /** 拉取一次快照。 */
    CollectionServiceClient.FetchResult fetchSnapshot();
}
