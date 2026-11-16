package com.coolpilot.insight.source;

import com.coolpilot.insight.AbstractIntegrationTest;
import com.coolpilot.insight.common.exception.BizException;
import com.coolpilot.insight.common.exception.ErrorCode;
import com.coolpilot.insight.common.security.RequestContext;
import com.coolpilot.insight.source.application.KeywordService;
import com.coolpilot.insight.source.application.SourceQueryService;
import com.coolpilot.insight.source.dto.DataSourceView;
import com.coolpilot.insight.source.dto.KeywordView;
import com.coolpilot.insight.system.application.UserService;
import com.coolpilot.insight.system.infrastructure.persistence.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * source 模块（R3-02）：数据源能力状态 + 关键词去重与归属校验。
 */
class SourceModuleTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(1);
    private static final String PASSWORD = "Passw0rd!1234";

    @Autowired
    private SourceQueryService sourceQueryService;

    @Autowired
    private KeywordService keywordService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserMapper userMapper;

    private Long userA;
    private Long userB;

    @BeforeEach
    void setUp() {
        userA = register();
        userB = register();
    }

    @AfterEach
    void clear() {
        RequestContext.clear();
    }

    private Long register() {
        String phone = "134" + String.format("%08d", 40_000_000 + SEQ.getAndIncrement());
        userService.register(phone, PASSWORD, PASSWORD);
        return userMapper.selectByPhone(phone).getId();
    }

    private void login(Long userId, int role) {
        RequestContext.setUserId(userId);
        RequestContext.setRole(role);
    }

    @Test
    @DisplayName("数据源列表包含抖音，且能力状态被解析为对象")
    void listSourcesWithCapability() {
        List<DataSourceView> sources = sourceQueryService.list();

        DataSourceView douyin = sources.stream()
                .filter(s -> "DOUYIN".equals(s.getCode()))
                .findFirst().orElse(null);

        assertThat(douyin).isNotNull();
        assertThat(douyin.getCapability()).containsEntry("comments", true);
        assertThat(douyin.getCapability())
                .as("关键词搜索未验证，必须是 false，不能假装可用")
                .containsEntry("keywordSearch", false);

        // 兼容期字段的语义在 SOURCE-04-06 之后变了：库中已是 v2，
        // capability 由 Desired **派生**（SOURCE-04-04 §2.7 · E3），
        // 不再原样回显旧文档，因此旧状态词 commentStatus / keywordStatus 不再出现。
        // 它们的信息没有丢：keywordSearch=false 就是 KEYWORD_SEARCH 的 desiredStatus=DISABLED，
        // 而"实现到什么程度"由 Observed 的 implementationStatus 表达（ADR-008 §2.4）。
        assertThat(douyin.getCapability())
                .as("旧状态词不得回到 Desired 的兼容视图（它本就属于 Observed）")
                .doesNotContainKeys("commentStatus", "keywordStatus");
    }

    @Test
    @DisplayName("不存在的平台返回 404 业务码")
    void unknownSource() {
        assertThatThrownBy(() -> sourceQueryService.findByCode("TIKTOK"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("同一用户重复添加关键词（大小写/空格不同）被判为重复")
    void duplicateKeywordRejected() {
        login(userA, 0);
        keywordService.create("DOUYIN", "Java AI");

        assertThatThrownBy(() -> keywordService.create("DOUYIN", " java ai "))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE);
    }

    @Test
    @DisplayName("不同用户可以添加同名关键词")
    void sameKeywordDifferentUsers() {
        login(userA, 0);
        keywordService.create("DOUYIN", "RAG");

        login(userB, 0);
        KeywordView created = keywordService.create("DOUYIN", "RAG");
        assertThat(created.getId()).isNotNull();
    }

    @Test
    @DisplayName("普通用户只能看到自己的关键词")
    void listIsOwnedScoped() {
        login(userA, 0);
        keywordService.create("DOUYIN", "only-a");

        login(userB, 0);
        List<KeywordView> bList = keywordService.list(null);
        assertThat(bList).noneMatch(k -> "only-a".equals(k.getKeyword()));

        login(userA, 0);
        assertThat(keywordService.list(null)).anyMatch(k -> "only-a".equals(k.getKeyword()));
    }

    @Test
    @DisplayName("删除他人关键词被拒绝")
    void cannotDeleteOthersKeyword() {
        login(userA, 0);
        KeywordView k = keywordService.create("DOUYIN", "mine");

        login(userB, 0);
        assertThatThrownBy(() -> keywordService.delete(k.getId()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("空关键词与超长关键词被拒绝")
    void invalidKeywords() {
        login(userA, 0);

        assertThatThrownBy(() -> keywordService.create("DOUYIN", "   "))
                .isInstanceOf(BizException.class);

        String tooLong = "a".repeat(129);
        assertThatThrownBy(() -> keywordService.create("DOUYIN", tooLong))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("128");
    }

    @Test
    @DisplayName("禁用后状态为 DISABLED")
    void disableKeyword() {
        login(userA, 0);
        KeywordView k = keywordService.create("DOUYIN", "to-disable");

        keywordService.disable(k.getId());

        KeywordView reloaded = keywordService.list(null).stream()
                .filter(v -> v.getId().equals(k.getId())).findFirst().orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("DISABLED");
    }

    @Test
    @DisplayName("未登录不能创建关键词")
    void anonymousCannotCreate() {
        RequestContext.clear();
        assertThatThrownBy(() -> keywordService.create("DOUYIN", "anon"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }
}
