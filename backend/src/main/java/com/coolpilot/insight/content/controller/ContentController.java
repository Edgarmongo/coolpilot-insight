package com.coolpilot.insight.content.controller;

import com.coolpilot.insight.common.security.RequireRole;
import com.coolpilot.insight.common.web.PageResult;
import com.coolpilot.insight.common.web.Result;
import com.coolpilot.insight.content.application.ContentAccessService;
import com.coolpilot.insight.content.application.ContentQueryService;
import com.coolpilot.insight.content.domain.Content;
import com.coolpilot.insight.content.domain.ContentComment;
import com.coolpilot.insight.content.dto.CommentView;
import com.coolpilot.insight.content.dto.ContentView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 内容查询接口（读新表 ct_*）。
 *
 * <p>与现有 {@code /api/comment/list} 并存：后者读旧表，本控制器读新表。
 * 两套结果已由 {@code ContentReadParityTest} 证明一致；待前端切换后删除旧实现。
 */
@RestController
@RequestMapping("/api/v1/content")
public class ContentController {

    private final ContentQueryService queryService;
    private final ContentAccessService accessService;

    public ContentController(ContentQueryService queryService, ContentAccessService accessService) {
        this.queryService = queryService;
        this.accessService = accessService;
    }

    @RequireRole(role = 0)
    @GetMapping("/contents")
    public Result<PageResult<ContentView>> listContents(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<ContentView> records = queryService.listContents(page, size)
                .stream().map(ContentController::toView).toList();
        return Result.ok(PageResult.of(records, queryService.countContents(),
                Math.max(page, 1), Math.min(Math.max(size, 1), 200)));
    }

    @RequireRole(role = 0)
    @GetMapping("/contents/{id}")
    public Result<ContentView> getContent(@PathVariable Long id) {
        accessService.requireRead(id);
        Content c = queryService.findById(id);
        return c == null ? Result.ok(null) : Result.ok(toView(c));
    }

    @RequireRole(role = 0)
    @GetMapping("/contents/{id}/comments")
    public Result<PageResult<CommentView>> listComments(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        accessService.requireRead(id);
        List<CommentView> records = queryService.listComments(id, page, size)
                .stream().map(ContentController::toView).toList();
        return Result.ok(PageResult.of(records, queryService.countComments(id),
                Math.max(page, 1), Math.min(Math.max(size, 1), 200)));
    }

    private static ContentView toView(Content c) {
        ContentView v = new ContentView();
        v.setId(c.getId());
        v.setSourceId(c.getSourceId());
        v.setExternalContentId(c.getExternalContentId());
        v.setContentType(c.getContentType());
        v.setTitle(c.getTitle());
        v.setSourceUrl(c.getSourceUrl());
        v.setPublishedAt(c.getPublishedAt());
        v.setNormalizeStatus(c.getNormalizeStatus());
        return v;
    }

    private static CommentView toView(ContentComment c) {
        CommentView v = new CommentView();
        v.setId(c.getId());
        v.setVideoId(c.getContentId());
        v.setCid(c.getExternalCommentId());
        v.setParentCid(c.getParentExternalId());
        v.setNickname(c.getNickname());
        v.setContent(c.getText());
        v.setLikes(c.getLikes());
        v.setIpRegion(c.getIpRegion());
        v.setPublishTime(c.getPublishedAt());
        v.setIsReply(c.getIsReply());
        return v;
    }
}
