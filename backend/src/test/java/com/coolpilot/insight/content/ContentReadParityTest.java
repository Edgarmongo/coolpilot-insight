package com.coolpilot.insight.content;

import com.coolpilot.insight.AbstractIntegrationTest;
import com.coolpilot.insight.content.application.ContentQueryService;
import com.coolpilot.insight.content.domain.Content;
import com.coolpilot.insight.content.domain.ContentComment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 新旧读路径一致性（R3-05b-3 的前置证据）。
 *
 * <p>切换 read path 之前必须先证明：用新表查出来的结果与旧表**完全一致**。
 * 否则切换后页面会少数据或串数据，而这种问题在人工点几下时往往看不出来。
 *
 * <p>本次迁入的是历史数据（3 内容 / 19 评论），测试直接以旧表为基准做对比。
 */
class ContentReadParityTest extends AbstractIntegrationTest {

    @Autowired
    private ContentQueryService queryService;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 造一批旧表数据，再执行与 V12 相同的迁移语句。
     *
     * <p>为什么在测试里造数据而不是直接用业务库：测试跑在独立的 Testcontainers 库上，
     * 旧表是空的。这样既验证查询映射（列名/别名），也验证迁移 SQL 本身可用。
     */
    @org.junit.jupiter.api.BeforeEach
    void seedAndMigrate() {
        // 只清理本测试自己的数据：video 被 collection_task_item 引用，不能整表删
        jdbc.update("DELETE FROM ct_comment WHERE content_id IN (5001,5002)");
        jdbc.update("DELETE FROM ct_content WHERE id IN (5001,5002)");
        jdbc.update("DELETE FROM comment WHERE video_id IN (5001,5002)");
        jdbc.update("DELETE FROM collection_task_item WHERE video_id IN (5001,5002)");
        jdbc.update("DELETE FROM collection_checkpoint WHERE task_item_id NOT IN (SELECT id FROM collection_task_item)");
        jdbc.update("DELETE FROM video WHERE id IN (5001,5002)");

        jdbc.update("INSERT INTO video (id, platform, aweme_id, title, author_id, author_name, "
                + "publish_time, digg_count, comment_count, status) VALUES "
                + "(5001,'douyin','aweme-a','标题A','author-1','作者甲',NOW(),10,2,1),"
                + "(5002,'douyin','aweme-b','标题B','author-2','作者乙',NOW(),20,1,1)");

        jdbc.update("INSERT INTO comment (platform, cid, dedup_key, dedup_strategy, video_id, "
                + "parent_cid, is_reply, nickname, content, likes, publish_time) VALUES "
                + "('douyin','c1','k1','CID',5001,NULL,0,'用户甲','评论一',3,NOW()),"
                + "('douyin','c2','k2','CID',5001,'c1',1,'用户乙','评论二',1,NOW()),"
                + "('douyin','c3','k3','CID',5002,NULL,0,'用户丙','评论三',0,NOW())");

        jdbc.update("INSERT INTO ct_content (id, source_id, external_content_id, content_type, title, "
                + "author_id, normalize_status, published_at) "
                + "SELECT v.id, ds.id, v.aweme_id, 'VIDEO', v.title, NULL, 'NORMALIZED', v.publish_time "
                + "FROM video v JOIN ds_source ds ON ds.code = UPPER(v.platform) COLLATE utf8mb4_unicode_ci "
                + "WHERE v.id IN (5001,5002)");

        jdbc.update("INSERT INTO ct_comment (id, content_id, source_id, external_comment_id, "
                + "parent_external_id, is_reply, nickname, text, likes, published_at, dedup_key, dedup_strategy) "
                + "SELECT cm.id, cm.video_id, ds.id, cm.cid, cm.parent_cid, cm.is_reply, cm.nickname, "
                + "cm.content, cm.likes, cm.publish_time, cm.dedup_key, cm.dedup_strategy "
                + "FROM comment cm JOIN video v ON v.id = cm.video_id "
                + "JOIN ds_source ds ON ds.code = UPPER(v.platform) COLLATE utf8mb4_unicode_ci "
                + "WHERE cm.video_id IN (5001,5002)");
    }

    /** 本用例专用 id，避免与其他用例的数据串扰 */
    private static final List<Long> SEED_IDS = List.of(5001L, 5002L);

    @Test
    @DisplayName("迁移后 id 与旧表一一对应")
    void contentIdsMatchLegacy() {
        for (Long id : SEED_IDS) {
            Integer inLegacy = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM video WHERE id = ?", Integer.class, id);
            Integer inNew = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ct_content WHERE id = ?", Integer.class, id);
            assertThat(inNew).as("id %s 应在新表存在", id).isEqualTo(inLegacy).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("每个内容的评论数与旧表一致")
    void commentCountPerContentMatchesLegacy() {
        for (Long id : SEED_IDS) {
            Integer legacy = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM comment WHERE video_id = ?", Integer.class, id);
            assertThat(queryService.countComments(id))
                    .as("content %s 的评论数应与旧表一致", id)
                    .isEqualTo(legacy);
        }
    }

    @Test
    @DisplayName("评论正文与点赞数逐条一致（抽查首个内容）")
    void commentFieldsMatchLegacy() {
        Long contentId = SEED_IDS.get(0);

        List<ContentComment> mine = queryService.listComments(contentId, 1, 100);
        Integer legacy = jdbc.queryForObject(
                "SELECT COUNT(*) FROM comment WHERE video_id = ?", Integer.class, contentId);
        assertThat(mine).hasSize(legacy);

        // 用 (cid, 文本, 点赞) 三元组比对，避免排序差异导致误判
        for (ContentComment c : mine) {
            Integer matched = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM comment WHERE video_id = ? AND cid = ? AND content = ? AND likes = ?",
                    Integer.class, contentId, c.getExternalCommentId(), c.getText(), c.getLikes());
            assertThat(matched).as("评论 %s 应在旧表中存在且字段一致", c.getExternalCommentId())
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("内容标题与旧表一致")
    void contentFieldsMatchLegacy() {
        for (Long id : SEED_IDS) {
            Content c = queryService.findById(id);
            assertThat(c).as("id %s 应能读出", id).isNotNull();
            String legacyTitle = jdbc.queryForObject(
                    "SELECT title FROM video WHERE id = ?", String.class, id);
            assertThat(c.getTitle()).isEqualTo(legacyTitle);
        }
    }

    @Test
    @DisplayName("分页参数做边界归一")
    void pagingNormalized() {
        List<Content> page = queryService.listContents(0, 0);
        assertThat(page).hasSizeLessThanOrEqualTo(1);
    }
}
