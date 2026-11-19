package com.coolpilot.insight.collection.application;

import com.coolpilot.insight.integration.collection.CollectionClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 把采集执行包进能力闸门（SOURCE-04-FOLLOWUP-FIX-02 · P1）。
 *
 * <p>{@code integration} 层只负责"怎么执行"（HTTP / 子进程），
 * "能不能执行"放在这里——两层不在同一个包里，
 * 这样 integration 不会反过来依赖 application。
 *
 * <p>{@code @Primary}：业务侧按类型注入 {@link CollectionClient} 时拿到的是**带闸门的**这个，
 * 而不是原始实现。原始实现用 {@code rawCollectionClient} 限定名才能取到。
 */
@Configuration
public class CollectionClientGateConfig {

    private static final Logger log = LoggerFactory.getLogger(CollectionClientGateConfig.class);

    @Bean
    @Primary
    public CollectionClient collectionClient(
            @Qualifier("rawCollectionClient") CollectionClient raw,
            CollectionAdmissionGuard guard) {
        log.info("[collection] 采集执行已统一经过能力准入：实现={}",
                raw.getClass().getSimpleName());
        return new AdmissionGatedCollectionClient(raw, guard);
    }
}
