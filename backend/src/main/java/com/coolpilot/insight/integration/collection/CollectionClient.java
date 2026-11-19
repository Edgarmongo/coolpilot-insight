package com.coolpilot.insight.integration.collection;

import com.coolpilot.insight.integration.common.CommandResult;

/**
 * 采集服务客户端。
 *
 * <p>业务侧只依赖这个接口，不关心底层是子进程还是 HTTP。
 * R3-04 把采集服务容器化并暴露 HTTP 后，只需换实现，调用方不动。
 */
public interface CollectionClient {

    /**
     * 采集单个内容的评论。
     *
     * @param taskId / itemId 透传给脚本，用于日志与断点关联
     */
    CommandResult collectComments(String externalContentId, int maxComments,
                                  boolean useLogin, Long taskId, Long itemId);

    /** 按关键词发现内容。 */
    CommandResult searchByKeyword(String keyword, int max);
}
