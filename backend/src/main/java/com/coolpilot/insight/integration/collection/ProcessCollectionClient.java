package com.coolpilot.insight.integration.collection;

import com.coolpilot.insight.integration.common.CommandResult;
import com.coolpilot.insight.integration.common.ProcessRunner;
import com.coolpilot.insight.integration.config.AppProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 子进程实现：直接调 collection-service 的抖音适配器脚本。
 *
 * <p><b>已标记 deprecated</b>（SOURCE-06 · D3）：采集执行已切到 HTTP 作业派发
 * （{@link HttpCollectionClient}）。本实现保留一个版本周期作为回滚通道，
 * 由 {@code app.collection.executor=process} 显式启用。
 *
 * <p>注意它<b>不经过任何能力准入</b>——只要进程能起，脚本就会跑。
 * 这正是 SOURCE-06 要解决的问题，因此不得再把新功能挂到这里。
 */
@Deprecated
public class ProcessCollectionClient implements CollectionClient {

    /** 采集单个内容可能翻页较久，给 10 分钟；超时即视为失败，由图上层重试。 */
    private static final int COLLECT_TIMEOUT_SECONDS = 600;
    private static final int SEARCH_TIMEOUT_SECONDS = 300;

    private final AppProperties props;
    private final ProcessRunner runner;

    public ProcessCollectionClient(AppProperties props, ProcessRunner runner) {
        this.props = props;
        this.runner = runner;
    }

    @Override
    public CommandResult collectComments(String externalContentId, int maxComments,
                                         boolean useLogin, Long taskId, Long itemId) {
        List<String> cmd = new ArrayList<>();
        cmd.add(props.getPythonExe());
        cmd.add("comment.py");
        cmd.add(externalContentId);
        cmd.add(String.valueOf(maxComments));
        if (useLogin) {
            cmd.add("--use-login");
        }

        Map<String, String> env = new HashMap<>();
        if (taskId != null) {
            env.put("COLLECT_TASK_ID", String.valueOf(taskId));
        }
        if (itemId != null) {
            env.put("COLLECT_ITEM_ID", String.valueOf(itemId));
        }

        return runner.run(cmd, props.getPythonDataDir(), env, COLLECT_TIMEOUT_SECONDS);
    }

    @Override
    public CommandResult searchByKeyword(String keyword, int max) {
        List<String> cmd = new ArrayList<>();
        cmd.add(props.getPythonExe());
        cmd.add("search.py");
        cmd.add(keyword);
        cmd.add(String.valueOf(max));
        return runner.run(cmd, props.getPythonDataDir(), Map.of(), SEARCH_TIMEOUT_SECONDS);
    }
}
