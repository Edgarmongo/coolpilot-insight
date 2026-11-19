package com.coolpilot.insight.content;

import com.coolpilot.insight.AbstractIntegrationTest;
import com.coolpilot.insight.content.application.ContentIngestService;
import com.coolpilot.insight.content.application.ContentIngestService.CommentInput;
import com.coolpilot.insight.content.application.ContentIngestService.ContentInput;
import com.coolpilot.insight.content.domain.Content;
import com.coolpilot.insight.content.infrastructure.persistence.ContentCommentMapper;
import com.coolpilot.insight.source.application.SourceQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内容入库（R3-05a）。这是新架构里唯一的写入口，幂等一旦失效会导致重复评论与重复计费。
 */
class ContentIngestTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(1);

    @Autowired
    private ContentIngestService ingest;

    @Autowired
    private ContentCommentMapper commentMapper;

    @Autowired
    private SourceQueryService sourceQueryService;

    private Long douyinSourceId() {
        return sourceQueryService.findByCode("DOUYIN").getId();
    }

    private String nextExternalId() {
        return "v" + SEQ.getAndIncrement() + "-" + System.nanoTime();
    }

    private ContentInput contentInput(String externalId) {
        ContentInput in = new ContentInput();
        in.setSourceId(douyinSourceId());
        in.setExternalContentId(externalId);
        in.setTitle("测试内容");
        in.setContentText("内容正文");
        in.setSourceUrl("https://example.invalid/" + externalId);
        in.setAuthorExternalId("author-1");
        in.setAuthorName("作者甲");
        in.setPublishedAt(LocalDateTime.now().minusDays(1));
        in.setRawPayload("{\"aweme_id\":\"" + externalId + "\"}");
        return in;
    }

    private CommentInput comment(String cid, String text) {
        CommentInput c = new CommentInput();
        c.setExternalCommentId(cid);
        c.setNickname("用户甲");
        c.setText(text);
        c.setLikes(1);
        c.setPublishedAt(LocalDateTime.now().minusHours(1));
        return c;
    }

    @Test
    @DisplayName("同一条内容重复入库不产生新行")
    void contentIsIdempotent() {
        String ext = nextExternalId();
        Content first = ingest.ingestContent(contentInput(ext));
        Content second = ingest.ingestContent(contentInput(ext));

        assertThat(first.getId()).isNotNull();
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(ingest.findByExternal(douyinSourceId(), ext)).isNotNull();
    }

    @Test
    @DisplayName("评论入库幂等：同一 cid 重复提交只留一条")
    void commentsAreIdempotent() {
        String ext = nextExternalId();
        Content c = ingest.ingestContent(contentInput(ext));
        Long sourceId = douyinSourceId();

        int first = ingest.ingestComments(c.getId(), sourceId,
                List.of(comment("cid-1", "第一条"), comment("cid-2", "第二条")));
        int second = ingest.ingestComments(c.getId(), sourceId,
                List.of(comment("cid-1", "第一条"), comment("cid-2", "第二条")));

        assertThat(first).isEqualTo(2);
        assertThat(second).as("重复提交不应新增").isZero();
        assertThat(commentMapper.countByContent(c.getId())).isEqualTo(2);
    }

    @Test
    @DisplayName("cid 缺失时用指纹去重，并标记策略为 FINGERPRINT")
    void commentWithoutCidUsesFingerprint() {
        String ext = nextExternalId();
        Content c = ingest.ingestContent(contentInput(ext));
        Long sourceId = douyinSourceId();

        CommentInput noCid = comment(null, "想学 RAG 但不知道从哪开始");
        int first = ingest.ingestComments(c.getId(), sourceId, List.of(noCid));
        int second = ingest.ingestComments(c.getId(), sourceId, List.of(noCid));

        assertThat(first).isEqualTo(1);
        assertThat(second).as("相同指纹不应重复插入").isZero();

        var saved = commentMapper.selectByContent(c.getId(), 10).get(0);
        assertThat(saved.getDedupStrategy()).isEqualTo("FINGERPRINT");
        assertThat(saved.getDedupKey()).contains("|fp|").doesNotContain("|cid|");
    }

    @Test
    @DisplayName("不同评论内容产生不同指纹")
    void differentTextsProduceDifferentFingerprint() {
        String ext = nextExternalId();
        Content c = ingest.ingestContent(contentInput(ext));
        Long sourceId = douyinSourceId();

        int n = ingest.ingestComments(c.getId(), sourceId, List.of(
                comment(null, "想学 Java"),
                comment(null, "想学 Python")));

        assertThat(n).isEqualTo(2);
    }

    @Test
    @DisplayName("带 parentExternalId 的评论标记为回复")
    void replyFlagged() {
        String ext = nextExternalId();
        Content c = ingest.ingestContent(contentInput(ext));

        CommentInput reply = comment("cid-reply", "回复内容");
        reply.setParentExternalId("cid-parent");
        ingest.ingestComments(c.getId(), douyinSourceId(), List.of(reply));

        var saved = commentMapper.selectByContent(c.getId(), 10).get(0);
        assertThat(saved.getIsReply()).isEqualTo(1);
        assertThat(saved.getParentExternalId()).isEqualTo("cid-parent");
    }

    @Test
    @DisplayName("原始响应按哈希幂等：同一 payload 只归档一次")
    void rawArchiveIsIdempotent() {
        String ext = nextExternalId();
        ContentInput in = contentInput(ext);
        Content first = ingest.ingestContent(in);
        Content second = ingest.ingestContent(in);

        assertThat(second.getRawArchiveId()).isEqualTo(first.getRawArchiveId());
    }

    @Test
    @DisplayName("同一作者重复出现只建一条作者记录")
    void authorIsReused() {
        Content a = ingest.ingestContent(contentInput(nextExternalId()));
        Content b = ingest.ingestContent(contentInput(nextExternalId()));
        assertThat(a.getAuthorId()).isEqualTo(b.getAuthorId());
    }
}
