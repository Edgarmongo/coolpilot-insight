package com.coolpilot.insight.collection.application;

import com.coolpilot.insight.integration.collection.CollectionClient;
import com.coolpilot.insight.integration.common.CommandResult;

/**
 * 采集能力执行的**唯一闸门**（SOURCE-04-FOLLOWUP-FIX-02 · P1）。
 *
 * <h3>为什么必须有这一层</h3>
 * FIX-01 之前，能力准入只在 {@code POST /v1/jobs} 上生效；而 backend 内部还有一条
 * 直接调用采集器的路径（关键词搜索 → 子进程），它**完全不看 Effective**。
 * 结果是同一个能力有两条执行路径、两套判定标准——一条拒绝、一条放行。
 *
 * <pre>
 *   正确：调用方 → 本类（准入）→ 具体实现（HTTP / 子进程）
 *   错误：调用方 A → 准入 → 实现
 *         调用方 B →────────────→ 实现
 * </pre>
 *
 * <p>把闸门放在 {@link CollectionClient} 装饰器上，而不是放在某个调用方里：
 * 接口只有这几个方法，**新增方法就必须在这里加一行准入**，
 * 而新增调用方则自动被覆盖——这是唯一能保证"覆盖所有入口"的位置。
 *
 * <h3>拒绝时的行为</h3>
 * 抛 {@code BizException(STATE_CONFLICT)}，**委托对象一次都不会被调用**：
 * 不起子进程、不发 HTTP、不产生任何平台请求。理由与是否"反正会失败"无关——
 * 未通过准入的能力，连尝试都不该发生。
 */
public class AdmissionGatedCollectionClient implements CollectionClient {

    /** 与 SourceQueryService 的平台码一致。整条链路当前只处理抖音。 */
    private static final String SOURCE_DOUYIN = "DOUYIN";
    private static final String CAPABILITY_COMMENT = "COMMENT";
    private static final String CAPABILITY_KEYWORD_SEARCH = "KEYWORD_SEARCH";

    private final CollectionClient delegate;
    private final CollectionAdmissionGuard guard;

    public AdmissionGatedCollectionClient(CollectionClient delegate,
                                          CollectionAdmissionGuard guard) {
        this.delegate = delegate;
        this.guard = guard;
    }

    @Override
    public CommandResult collectComments(String externalContentId, int maxComments,
                                         boolean useLogin, Long taskId, Long itemId) {
        guard.requireExecutable(SOURCE_DOUYIN, CAPABILITY_COMMENT);
        return delegate.collectComments(externalContentId, maxComments, useLogin, taskId, itemId);
    }

    @Override
    public CommandResult searchByKeyword(String keyword, int max) {
        // KEYWORD_SEARCH 至今没有真实平台验证证据（SOURCE-01 记录"风控行为未知"）。
        // 该能力经准入会被判 NOT_EXECUTABLE —— 这正是期望行为：
        // 未验证的能力不得执行，而不是"因为搜索能用就先放着"。
        guard.requireExecutable(SOURCE_DOUYIN, CAPABILITY_KEYWORD_SEARCH);
        return delegate.searchByKeyword(keyword, max);
    }
}
