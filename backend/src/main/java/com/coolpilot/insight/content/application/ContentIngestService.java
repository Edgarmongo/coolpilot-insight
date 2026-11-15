package com.coolpilot.insight.content.application;

import com.coolpilot.insight.content.domain.Author;
import com.coolpilot.insight.content.domain.Content;
import com.coolpilot.insight.content.domain.ContentComment;
import com.coolpilot.insight.content.infrastructure.persistence.AuthorMapper;
import com.coolpilot.insight.content.infrastructure.persistence.ContentCommentMapper;
import com.coolpilot.insight.content.infrastructure.persistence.ContentMapper;
import com.coolpilot.insight.content.infrastructure.persistence.RawArchiveMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 内容入库（唯一写入口）。
 *
 * <p>三条规则：
 * 1. **幂等**：同一内容/评论重复入库不产生新行（双唯一键 + 预检查）；
 * 2. **原始与标准化分离**：原始响应存 {@code ct_raw_archive}（按哈希去重），
 *    业务字段存 {@code ct_content}；
 * 3. **cid 缺失时不能丢数据**：用指纹做 dedupKey，并标记策略为 FINGERPRINT。
 *
 * <p>Python 侧不再直接写库——它只返回结构化结果，由本类落库。
 * 这样权限、审计、幂等校验都在 Java 一处收口。
 */
@Service
public class ContentIngestService {

    private static final Logger log = LoggerFactory.getLogger(ContentIngestService.class);

    private final ContentMapper contentMapper;
    private final ContentCommentMapper commentMapper;
    private final AuthorMapper authorMapper;
    private final RawArchiveMapper rawArchiveMapper;

    public ContentIngestService(ContentMapper contentMapper,
                                ContentCommentMapper commentMapper,
                                AuthorMapper authorMapper,
                                RawArchiveMapper rawArchiveMapper) {
        this.contentMapper = contentMapper;
        this.commentMapper = commentMapper;
        this.authorMapper = authorMapper;
        this.rawArchiveMapper = rawArchiveMapper;
    }

    /** 入库一条内容（含作者与原始归档）。返回内容 id。 */
    @Transactional
    public Content ingestContent(ContentInput input) {
        Long authorId = resolveAuthor(input.sourceId, input.authorExternalId, input.authorName);

        Long rawId = null;
        if (input.rawPayload != null && !input.rawPayload.isEmpty()) {
            String hash = sha256(input.rawPayload);
            rawId = rawArchiveMapper.selectId(input.sourceId, input.externalContentId, hash);
            if (rawId == null) {
                RawArchiveMapper.ArchiveRow row = new RawArchiveMapper.ArchiveRow();
                row.sourceId = input.sourceId;
                row.externalId = input.externalContentId;
                row.contentType = input.contentType == null ? "VIDEO" : input.contentType;
                row.payload = input.rawPayload;
                row.hash = hash;
                rawArchiveMapper.insert(row);
                rawId = row.id;
            }
        }

        Content existing = contentMapper.selectByExternal(input.sourceId, input.externalContentId);
        if (existing != null) {
            // 已存在则更新标准化字段与归档引用，不新建行
            existing.setTitle(input.title);
            existing.setContentText(input.contentText);
            existing.setSourceUrl(input.sourceUrl);
            existing.setAuthorId(authorId != null ? authorId : existing.getAuthorId());
            existing.setRawArchiveId(rawId != null ? rawId : existing.getRawArchiveId());
            existing.setPublishedAt(input.publishedAt != null ? input.publishedAt : existing.getPublishedAt());
            existing.setMetricsJson(input.metricsJson);
            existing.setNormalizeStatus("NORMALIZED");
            contentMapper.updateNormalized(existing);
            return existing;
        }

        Content c = new Content();
        c.setSourceId(input.sourceId);
        c.setExternalContentId(input.externalContentId);
        c.setContentType(input.contentType == null ? "VIDEO" : input.contentType);
        c.setTitle(input.title);
        c.setContentText(input.contentText);
        c.setSourceUrl(input.sourceUrl);
        c.setAuthorId(authorId);
        c.setRawArchiveId(rawId);
        c.setNormalizeStatus("NORMALIZED");
        c.setPublishedAt(input.publishedAt);
        c.setCollectedAt(LocalDateTime.now());
        c.setMetricsJson(input.metricsJson);
        contentMapper.insert(c);
        return c;
    }

    /** 批量入库评论，返回新插入条数（已存在的跳过）。 */
    @Transactional
    public int ingestComments(Long contentId, Long sourceId, List<CommentInput> comments) {
        int inserted = 0;
        for (CommentInput in : comments) {
            String dedupKey = buildDedupKey(sourceId, in);
            String strategy = (in.externalCommentId == null || in.externalCommentId.isBlank())
                    ? "FINGERPRINT" : "CID";

            if (commentMapper.countByDedupKey(dedupKey) > 0) {
                continue;
            }
            if (in.externalCommentId != null && !in.externalCommentId.isBlank()
                    && commentMapper.countByExternal(sourceId, in.externalCommentId) > 0) {
                continue;
            }

            ContentComment c = new ContentComment();
            c.setContentId(contentId);
            c.setSourceId(sourceId);
            c.setExternalCommentId(in.externalCommentId);
            c.setParentExternalId(in.parentExternalId);
            c.setIsReply(in.parentExternalId != null && !in.parentExternalId.isBlank() ? 1 : 0);
            c.setNickname(in.nickname == null ? "" : in.nickname);
            c.setText(in.text == null ? "" : in.text);
            c.setLikes(in.likes == null ? 0 : in.likes);
            c.setIpRegion(in.ipRegion);
            c.setPublishedAt(in.publishedAt);
            c.setDedupKey(dedupKey);
            c.setDedupStrategy(strategy);

            try {
                commentMapper.insert(c);
                inserted++;
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 并发下预检查可能失效，唯一键是最后一道闸；此处视为已存在而非失败
                log.debug("[ingest] 评论并发重复，已跳过 dedupKey={}", dedupKey);
            }
        }
        return inserted;
    }

    public Content findByExternal(Long sourceId, String externalContentId) {
        return contentMapper.selectByExternal(sourceId, externalContentId);
    }

    // ---------------------------------------------------------------- 内部

    private Long resolveAuthor(Long sourceId, String externalAuthorId, String displayName) {
        if (externalAuthorId == null || externalAuthorId.isBlank()) {
            return null;
        }
        Author existing = authorMapper.selectByExternal(sourceId, externalAuthorId);
        if (existing != null) {
            if (displayName != null && !displayName.equals(existing.getDisplayName())) {
                existing.setDisplayName(displayName);
                authorMapper.touch(existing);
            }
            return existing.getId();
        }
        Author a = new Author();
        a.setSourceId(sourceId);
        a.setExternalAuthorId(externalAuthorId);
        a.setDisplayName(displayName);
        authorMapper.insert(a);
        return a.getId();
    }

    /**
     * 去重键：优先用平台 cid；缺失时用「昵称+文本+发布时间」指纹。
     *
     * <p>必须带 sourceId 前缀——不同平台的 cid 可能撞号。
     */
    static String buildDedupKey(Long sourceId, CommentInput in) {
        if (in.externalCommentId != null && !in.externalCommentId.isBlank()) {
            return sourceId + "|cid|" + in.externalCommentId;
        }
        String fp = (in.nickname == null ? "" : in.nickname)
                + "|" + (in.text == null ? "" : in.text)
                + "|" + (in.publishedAt == null ? "" : in.publishedAt);
        return sourceId + "|fp|" + sha256(fp).substring(0, 32);
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    // ---------------------------------------------------------------- 入参

    @lombok.Data
    public static class ContentInput {
        private Long sourceId;
        private String externalContentId;
        private String contentType;
        private String title;
        private String contentText;
        private String sourceUrl;
        private String authorExternalId;
        private String authorName;
        private LocalDateTime publishedAt;
        private String metricsJson;
        /** 平台原始响应（可为空） */
        private String rawPayload;
    }

    @lombok.Data
    public static class CommentInput {
        private String externalCommentId;
        private String parentExternalId;
        private String nickname;
        private String text;
        private Integer likes;
        private String ipRegion;
        private LocalDateTime publishedAt;
    }
}
