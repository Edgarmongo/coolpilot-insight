package com.coolpilot.insight.content;

import com.coolpilot.insight.AbstractIntegrationTest;
import com.coolpilot.insight.common.security.RequestContext;
import com.coolpilot.insight.content.application.ContentAccessService;
import com.coolpilot.insight.collection.domain.CollectionTask;
import com.coolpilot.insight.collection.domain.CollectionTaskItem;
import com.coolpilot.insight.entity.Video;
import com.coolpilot.insight.entity.VideoGrant;
import com.coolpilot.insight.collection.infrastructure.persistence.CollectionTaskItemMapper;
import com.coolpilot.insight.collection.infrastructure.persistence.CollectionTaskMapper;
import com.coolpilot.insight.mapper.VideoGrantMapper;
import com.coolpilot.insight.mapper.VideoMapper;
import com.coolpilot.insight.common.exception.BizException;
import com.coolpilot.insight.common.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 内容可见性判定（R2-02 从 GovernanceService 拆出的业务规则）。
 *
 * <p>这是本次拆分最需要护栏的地方：判定写错会直接导致**越权读取**或**正常用户看不到数据**。
 * 三条规则各自一条用例，另加「无登录不可读」与「集合视图」。
 */
class ContentAccessServiceTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(1);
    private static final String PASSWORD = "Passw0rd!1234";
    /** V6 种子写入的管理员（role=1） */
    private static final Long ADMIN_ID = 1L;

    private Long ownerId;
    private Long strangerId;

    @Autowired
    private ContentAccessService accessService;

    @Autowired
    private VideoMapper videoMapper;

    @Autowired
    private CollectionTaskMapper taskMapper;

    @Autowired
    private CollectionTaskItemMapper itemMapper;

    @Autowired
    private VideoGrantMapper grantMapper;

    @Autowired
    private com.coolpilot.insight.system.application.UserService userService;

    @Autowired
    private com.coolpilot.insight.system.infrastructure.persistence.UserMapper userMapper;

    @BeforeEach
    void createUsers() {
        ownerId = register();
        strangerId = register();
    }

    private Long register() {
        String phone = "137" + String.format("%08d", 30_000_000 + SEQ.getAndIncrement());
        userService.register(phone, PASSWORD, PASSWORD);
        return userMapper.selectByPhone(phone).getId();
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    private void login(Long userId, int role) {
        RequestContext.setUserId(userId);
        RequestContext.setRole(role);
    }

    private Video newVideo() {
        Video v = new Video();
        v.setAwemeId("acc-" + SEQ.getAndIncrement());
        v.setTitle("可见性测试视频");
        v.setStatus(1);
        v.setDiggCount(0L);
        videoMapper.insert(v);
        return v;
    }

    private void giveOwnTask(Long ownerId, Video v) {
        CollectionTask t = new CollectionTask();
        t.setTaskNo("ACC" + System.nanoTime() + "-" + SEQ.getAndIncrement());
        t.setTaskType("COLLECTION");
        t.setStatus("SUCCESS");
        t.setOwnerUserId(ownerId);
        t.setTotalItems(1);
        t.setSuccessItems(1);
        t.setFailedItems(0);
        taskMapper.insert(t);

        CollectionTaskItem item = new CollectionTaskItem();
        item.setTaskId(t.getId());
        item.setVideoId(v.getId());
        item.setStatus("SUCCESS");
        item.setRetryCount(0);
        item.setMaxRetry(3);
        item.setRequestedMax(300);
        item.setCollectedCount(0L);
        itemMapper.insert(item);
    }

    private void grant(Long userId, Video v) {
        VideoGrant g = new VideoGrant();
        g.setGranteeUserId(userId);
        g.setVideoId(v.getId());
        g.setPermission("READ");
        g.setGrantedBy(ADMIN_ID);
        grantMapper.insert(g);
    }

    @Test
    @DisplayName("管理员可读任意内容")
    void adminCanReadAnything() {
        Video v = newVideo();
        login(ADMIN_ID, 1);

        assertThat(accessService.canRead(v.getId())).isTrue();
        assertThat(accessService.readableContentIds()).as("管理员不受内容集合限制").isNull();
    }

    @Test
    @DisplayName("本人采集过该内容则可读（不依赖显式授权）")
    void ownerCanRead() {
        Video v = newVideo();
        giveOwnTask(ownerId, v);
        login(ownerId, 0);

        assertThat(accessService.canRead(v.getId())).isTrue();
        assertThat(accessService.readableContentIds()).contains(v.getId());
    }

    @Test
    @DisplayName("被显式授权者可读（授权未过期）")
    void grantedUserCanRead() {
        Video v = newVideo();
        grant(strangerId, v);
        login(strangerId, 0);

        assertThat(accessService.canRead(v.getId())).isTrue();
        assertThat(accessService.readableContentIds()).contains(v.getId());
    }

    @Test
    @DisplayName("无关用户不可读，且 requireRead 抛 SecurityException")
    void strangerCannotRead() {
        Video v = newVideo();
        login(strangerId, 0);

        assertThat(accessService.canRead(v.getId())).isFalse();
        assertThat(accessService.readableContentIds()).doesNotContain(v.getId());
        assertThatThrownBy(() -> accessService.requireRead(v.getId()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("未登录一律不可读")
    void anonymousCannotRead() {
        Video v = newVideo();
        RequestContext.clear();

        assertThat(accessService.canRead(v.getId())).isFalse();
        assertThat(accessService.readableContentIds()).isEmpty();
    }

    @Test
    @DisplayName("授权过期后失效")
    void expiredGrantIsIgnored() {
        Video v = newVideo();
        VideoGrant g = new VideoGrant();
        g.setGranteeUserId(strangerId);
        g.setVideoId(v.getId());
        g.setPermission("READ");
        g.setGrantedBy(ADMIN_ID);
        g.setExpiresAt(LocalDateTime.now().minusDays(1));
        grantMapper.insert(g);
        login(strangerId, 0);

        assertThat(accessService.canRead(v.getId())).isFalse();
    }

    @Test
    @DisplayName("有权限时 requireRead 不抛异常")
    void requireReadPassesForOwner() {
        Video v = newVideo();
        giveOwnTask(ownerId, v);
        login(ownerId, 0);

        assertThatCode(() -> accessService.requireRead(v.getId())).doesNotThrowAnyException();
        List<Long> ids = accessService.readableContentIds();
        assertThat(ids).contains(v.getId());
    }

    @Test
    @DisplayName("内容 id 为空时不可读（边界）")
    void nullContentId() {
        login(ADMIN_ID, 1);
        assertThat(accessService.canRead(null)).isFalse();
    }
}
