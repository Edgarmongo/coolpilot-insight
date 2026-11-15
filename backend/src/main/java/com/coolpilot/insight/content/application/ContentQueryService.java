package com.coolpilot.insight.content.application;

import com.coolpilot.insight.content.domain.Content;
import com.coolpilot.insight.content.domain.ContentComment;
import com.coolpilot.insight.content.infrastructure.persistence.ContentCommentMapper;
import com.coolpilot.insight.content.infrastructure.persistence.ContentMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 内容读查询（面向新表 ct_*）。
 *
 * <p>存在的意义：在切换读路径前，先让新表的查询能力独立可用并可测。
 * 现有 {@code VideoService}/{@code CommentServiceImpl} 仍读旧表——
 * 等本服务与旧服务的一致性测试通过后，再逐个替换，避免一次改 12 个类。
 */
@Service
public class ContentQueryService {

    private final ContentMapper contentMapper;
    private final ContentCommentMapper commentMapper;

    public ContentQueryService(ContentMapper contentMapper, ContentCommentMapper commentMapper) {
        this.contentMapper = contentMapper;
        this.commentMapper = commentMapper;
    }

    public List<Content> listContents(int page, int size) {
        int current = Math.max(page, 1);
        int pageSize = Math.min(Math.max(size, 1), 200);
        return contentMapper.selectPage((current - 1) * pageSize, pageSize);
    }

    public long countContents() {
        return contentMapper.countAll();
    }

    public Content findByExternal(Long sourceId, String externalContentId) {
        return contentMapper.selectByExternal(sourceId, externalContentId);
    }

    public Content findById(Long contentId) {
        return contentMapper.selectById(contentId);
    }

    /** 评论分页。contentId 即旧 videoId（V12 已对齐主键）。 */
    public List<ContentComment> listComments(Long contentId, int page, int size) {
        int current = Math.max(page, 1);
        int pageSize = Math.min(Math.max(size, 1), 200);
        return commentMapper.selectPageByContent(contentId, (current - 1) * pageSize, pageSize);
    }

    public int countComments(Long contentId) {
        return commentMapper.countByContent(contentId);
    }
}
