package com.coolskyai.coolpilot.insight;

import com.coolskyai.coolpilot.insight.entity.Comment;
import com.coolskyai.coolpilot.insight.entity.Video;
import com.coolskyai.coolpilot.insight.mapper.CommentMapper;
import com.coolskyai.coolpilot.insight.mapper.VideoMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 校验 MyBatis 替换后的 CRUD 语义与原先 MyBatis-Plus 一致。
 *
 * <p>这组用例是本次 ORM 迁移的直接防回归网：MP 的「insert/updateById 跳过 null」
 * 与「自增主键回填」都是隐式契约，一旦被改坏，业务上表现为字段莫名被清空或拿到 null id。
 */
class MapperCrudSemanticsTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(1);

    @Autowired
    private VideoMapper videoMapper;

    @Autowired
    private CommentMapper commentMapper;

    private Video newVideo(String awemeId) {
        Video v = new Video();
        v.setAwemeId(awemeId);
        v.setTitle("测试标题");
        v.setAuthorName("测试作者");
        v.setDiggCount(10L);
        v.setStatus(1);
        return v;
    }

    @Test
    @DisplayName("insert 回填自增主键")
    void insertBackfillsGeneratedId() {
        Video v = newVideo("crud-" + SEQ.getAndIncrement());
        int affected = videoMapper.insert(v);

        assertThat(affected).isEqualTo(1);
        assertThat(v.getId()).as("自增主键必须回填到实体").isNotNull().isPositive();
    }

    @Test
    @DisplayName("updateById 跳过 null 字段，不会把未设置的列清空")
    void updateByIdSkipsNullFields() {
        Video v = newVideo("crud-" + SEQ.getAndIncrement());
        videoMapper.insert(v);

        // 只改标题，其余字段全部为 null
        Video patch = new Video();
        patch.setId(v.getId());
        patch.setTitle("改过的标题");
        videoMapper.updateById(patch);

        Video reloaded = videoMapper.selectById(v.getId());
        assertThat(reloaded.getTitle()).isEqualTo("改过的标题");
        assertThat(reloaded.getAuthorName()).as("未设置的字段必须保持原值").isEqualTo("测试作者");
        assertThat(reloaded.getDiggCount()).isEqualTo(10L);
    }

    @Test
    @DisplayName("selectById 命中与未命中")
    void selectById() {
        Video v = newVideo("crud-" + SEQ.getAndIncrement());
        videoMapper.insert(v);

        assertThat(videoMapper.selectById(v.getId())).isNotNull();
        assertThat(videoMapper.selectById(999_999_999L)).isNull();
    }

    @Test
    @DisplayName("deleteById 物理删除后查不到")
    void deleteById() {
        Video v = newVideo("crud-" + SEQ.getAndIncrement());
        videoMapper.insert(v);

        assertThat(videoMapper.deleteById(v.getId())).isEqualTo(1);
        assertThat(videoMapper.selectById(v.getId())).isNull();
    }

    @Test
    @DisplayName("按 aweme_id 查询可命中")
    void selectByAwemeId() {
        String awemeId = "crud-aweme-" + SEQ.getAndIncrement();
        videoMapper.insert(newVideo(awemeId));

        Video found = videoMapper.selectByAwemeId(awemeId);
        assertThat(found).isNotNull();
        assertThat(found.getAwemeId()).isEqualTo(awemeId);
    }

    @Test
    @DisplayName("评论双唯一键：同 cid 重复插入被拒绝，且不产生脏数据")
    void duplicateCidIsRejected() {
        Video v = newVideo("crud-" + SEQ.getAndIncrement());
        videoMapper.insert(v);

        String cid = "cid-" + SEQ.getAndIncrement();
        Comment first = newComment(v.getId(), cid, "dedup-" + cid);
        assertThat(commentMapper.insert(first)).isEqualTo(1);

        Comment dup = newComment(v.getId(), cid, "dedup-other-" + cid);
        assertThatCode(() -> commentMapper.insert(dup))
                .as("同一 platform + cid 必须被唯一键拦下")
                .isInstanceOf(RuntimeException.class);

        assertThat(commentMapper.selectById(dup.getId())).as("失败的插入不能落库").isNull();
    }

    @Test
    @DisplayName("评论双唯一键：同 dedup_key 不同 cid 也被拒绝")
    void duplicateDedupKeyIsRejected() {
        Video v = newVideo("crud-" + SEQ.getAndIncrement());
        videoMapper.insert(v);

        String dedup = "dedup-" + SEQ.getAndIncrement();
        commentMapper.insert(newComment(v.getId(), "cid-a-" + dedup, dedup));

        Comment dup = newComment(v.getId(), "cid-b-" + dedup, dedup);
        assertThatCode(() -> commentMapper.insert(dup))
                .as("同一 platform + dedup_key 必须被唯一键拦下")
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("无 cid 时靠指纹去重（cid 允许为 NULL）")
    void nullCidUsesFingerprintKey() {
        Video v = newVideo("crud-" + SEQ.getAndIncrement());
        videoMapper.insert(v);

        Comment c = newComment(v.getId(), null, "fp-" + SEQ.getAndIncrement());
        c.setDedupStrategy("FINGERPRINT");
        assertThat(commentMapper.insert(c)).isEqualTo(1);
        assertThat(commentMapper.selectById(c.getId()).getCid()).isNull();
    }

    private Comment newComment(Long videoId, String cid, String dedupKey) {
        Comment c = new Comment();
        c.setPlatform("douyin");
        c.setCid(cid);
        c.setDedupKey(dedupKey);
        c.setDedupStrategy(cid == null ? "FINGERPRINT" : "CID");
        c.setVideoId(videoId);
        c.setNickname("测试用户");
        c.setContent("测试评论内容");
        c.setPublishTime(LocalDateTime.now());
        c.setLikes(0);
        c.setIsReply(0);
        c.setReplyCount(0);
        return c;
    }
}
