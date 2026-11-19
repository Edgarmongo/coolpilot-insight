package com.coolpilot.insight.service.impl;

import com.coolpilot.insight.mapper.CommentMapper;
import com.coolpilot.insight.service.CommentService;
import com.coolpilot.insight.service.VideoService;
import com.coolpilot.insight.content.application.CommentTreeAssembler;
import com.coolpilot.insight.content.application.ContentAccessService;
import com.coolpilot.insight.system.application.PermissionService;
import com.coolpilot.insight.common.exception.BizException;
import com.coolpilot.insight.common.exception.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;

@Service
public class CommentServiceImpl implements CommentService {

    private final CommentMapper commentMapper;
    private final VideoService videoService;
    private final ContentAccessService contentAccess;
    private final PermissionService permission;

    public CommentServiceImpl(CommentMapper commentMapper, VideoService videoService,
                              ContentAccessService contentAccess, PermissionService permission) {
        this.commentMapper = commentMapper;
        this.videoService = videoService;
        this.contentAccess = contentAccess;
        this.permission = permission;
    }

    private static String sentimentText(Integer sentiment) {
        if (sentiment == null) {
            return null;
        }
        if (sentiment == 1) {
            return "positive";
        }
        if (sentiment == -1) {
            return "negative";
        }
        return "neutral";
    }

    private static String orderBy(String sort) {
        // 白名单排序，避免 ORDER BY 注入
        if ("likes_desc".equals(sort)) {
            return "c.likes DESC, c.id ASC";
        }
        if ("likes_asc".equals(sort)) {
            return "c.likes ASC, c.id ASC";
        }
        if ("time_asc".equals(sort)) {
            return "c.publish_time ASC, c.id ASC";
        }
        return "c.publish_time DESC, c.id DESC";
    }

    @Override
    public Map<String, Object> listByVideo(String videoId, int page, int size,
                                           String region, Integer sentiment, String sort) {
        Long vid = videoService.resolveInternalId(videoId);
        if (vid == null) {
            return emptyPage(page, size);
        }
        contentAccess.requireRead(vid);

        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 1);
        String sText = sentimentText(sentiment);

        long total = commentMapper.countCommentViews(vid, region, sText);
        List<Map<String, Object>> records = commentMapper.selectCommentViews(
                vid, region, sText, orderBy(sort), (safePage - 1) * safeSize, safeSize);
        return page(records, total, safePage, safeSize);
    }

    @Override
    public Map<String, Object> search(String keyword, String videoId, int page, int size) {
        Long vid = (videoId == null || videoId.isEmpty()) ? null : videoService.resolveInternalId(videoId);
        if (vid != null) {
            contentAccess.requireRead(vid);
        } else if (!permission.isAdmin()) {
            // 全局搜索仅管理员可用，避免越权遍历他人数据
            throw new BizException(ErrorCode.FORBIDDEN, "全局评论搜索仅管理员可用");
        }
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 1);
        long total = commentMapper.countSearchViews(keyword, vid);
        List<Map<String, Object>> records = commentMapper.searchCommentViews(
                keyword, vid, (safePage - 1) * safeSize, safeSize);
        return page(records, total, safePage, safeSize);
    }

    @Override
    public Map<String, Object> tree(String videoId, int limit, int rootLimit) {
        Long vid = videoService.resolveInternalId(videoId);
        if (vid == null) {
            return Collections.emptyMap();
        }
        contentAccess.requireRead(vid);

        int safeLimit = Math.min(Math.max(limit, 1), 2000);
        List<Map<String, Object>> rows = commentMapper.treeRows(vid, safeLimit);
        return CommentTreeAssembler.assemble(rows, rootLimit).toMap();
    }

    private static Map<String, Object> emptyPage(int page, int size) {
        return page(java.util.Collections.emptyList(), 0, page, size);
    }

    private static Map<String, Object> page(List<Map<String, Object>> records, long total, int page, int size) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", records);
        out.put("total", total);
        out.put("page", page);
        out.put("size", size);
        return out;
    }
}
