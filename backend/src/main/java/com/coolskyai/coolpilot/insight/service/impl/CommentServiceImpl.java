package com.coolskyai.coolpilot.insight.service.impl;

import com.coolskyai.coolpilot.insight.mapper.CommentMapper;
import com.coolskyai.coolpilot.insight.service.CommentService;
import com.coolskyai.coolpilot.insight.service.GovernanceService;
import com.coolskyai.coolpilot.insight.service.VideoService;
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
    private final GovernanceService governance;

    public CommentServiceImpl(CommentMapper commentMapper, VideoService videoService,
                              GovernanceService governance) {
        this.commentMapper = commentMapper;
        this.videoService = videoService;
        this.governance = governance;
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
        governance.requireReadComments(vid);

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
            governance.requireReadComments(vid);
        } else if (!governance.isAdmin()) {
            // 全局搜索仅管理员可用，避免越权遍历他人数据
            throw new SecurityException("全局评论搜索仅管理员可用");
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
        governance.requireReadComments(vid);

        int safeLimit = Math.min(Math.max(limit, 1), 2000);
        List<Map<String, Object>> rows = commentMapper.treeRows(vid, safeLimit);

        // cid → 节点；parent 缺失的视为一级
        Map<String, Map<String, Object>> byCid = new LinkedHashMap<>();
        List<Map<String, Object>> roots = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> node = new LinkedHashMap<>(r);
            node.put("replies", new ArrayList<Map<String, Object>>());
            String cid = r.get("cid") == null ? "" : String.valueOf(r.get("cid"));
            if (!cid.isEmpty()) {
                byCid.put(cid, node);
            }
            String parent = r.get("parentCid") == null ? "" : String.valueOf(r.get("parentCid"));
            boolean isRoot = parent.isEmpty() || "0".equals(parent);
            if (isRoot) {
                roots.add(node);
            }
        }
        // 二级挂到父节点；找不到父节点的降级为一级，避免数据丢失
        for (Map<String, Object> r : rows) {
            String parent = r.get("parentCid") == null ? "" : String.valueOf(r.get("parentCid"));
            String cid = r.get("cid") == null ? "" : String.valueOf(r.get("cid"));
            if (parent.isEmpty() || "0".equals(parent) || cid.isEmpty()) {
                continue;
            }
            Map<String, Object> p = byCid.get(parent);
            Map<String, Object> self = byCid.get(cid);
            if (p != null && self != null && p != self) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> children = (List<Map<String, Object>>) p.get("replies");
                children.add(self);
            } else if (self != null) {
                roots.add(self);
            }
        }

        int safeRoots = Math.min(Math.max(rootLimit, 1), 200);
        List<Map<String, Object>> page = roots.size() > safeRoots ? roots.subList(0, safeRoots) : roots;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("roots", new ArrayList<>(page));
        out.put("totalComments", rows.size());
        out.put("totalRoots", roots.size());
        out.put("shownRoots", page.size());
        return out;
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
