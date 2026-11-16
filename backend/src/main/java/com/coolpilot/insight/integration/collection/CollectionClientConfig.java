package com.coolpilot.insight.integration.collection;

import com.coolpilot.insight.integration.common.ProcessRunner;
import com.coolpilot.insight.integration.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 采集执行实现的选择（SOURCE-06 · D3）。
 *
 * <p>两种实现都实现了 {@link CollectionClient}，切换由配置决定：
 * {@code app.collection.executor=http|process}，<b>默认 http</b>。
 *
 * <p>为什么留 {@code process}：切换当天如果 HTTP 链路出问题，需要一条
 * 不依赖改代码的回滚路径。它被明确标记为 deprecated——
 * 保留不是因为它更好，而是因为回滚必须比修复快。
 */
@Configuration
public class CollectionClientConfig {

    private static final Logger log = LoggerFactory.getLogger(CollectionClientConfig.class);

    @Bean
    public CollectionClient collectionClient(
            AppProperties props,
            ProcessRunner runner,
            @Value("${app.collection.executor:http}") String executor,
            @Value("${app.collection-service-url:http://127.0.0.1:5001}") String baseUrl,
            @Value("${app.collection-internal-token:}") String internalToken,
            @Value("${app.collection.default-connector:DOUYIN_PUBLIC}") String connector) {

        ProcessCollectionClient process = new ProcessCollectionClient(props, runner);

        if ("process".equalsIgnoreCase(executor == null ? "" : executor.trim())) {
            log.warn("[collection] 采集执行使用子进程实现（app.collection.executor=process）。"
                    + "该实现已标记 deprecated，仅作回滚通道；正常应使用 http。");
            return process;
        }

        log.info("[collection] 采集执行走 HTTP 作业派发（executor=http, connector={}）。"
                + "关键词搜索仍由子进程执行：该能力尚无真实平台验证证据，"
                + "不满足作业派发的准入条件。", connector);
        return new HttpCollectionClient(baseUrl, internalToken, connector, process);
    }
}
