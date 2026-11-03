package com.coolskyai.coolpilot.insight.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.coolskyai.coolpilot.insight.entity.AnalysisReview;
import com.coolskyai.coolpilot.insight.entity.Comment;
import com.coolskyai.coolpilot.insight.entity.CommentAnalysis;
import com.coolskyai.coolpilot.insight.mapper.AnalysisReviewMapper;
import com.coolskyai.coolpilot.insight.mapper.AnalysisReviewMapperExt;
import com.coolskyai.coolpilot.insight.mapper.CommentMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * AI 结果人工复核。
 *
 * <p>语义（移植自 DYP 的 ai_review）：
 * <ul>
 *   <li>APPROVED —— 认可 AI 结论，不改数据</li>
 *   <li>REJECTED —— 判定 AI 结论错误，数据保留但标记不可信</li>
 *   <li>CORRECTED —— 人工给出正确标签，覆盖 comment_analysis 供看板使用</li>
 * </ul>
 *
 * <p>纠错时 {@code corrected_json} 会同时保存 before/after，AI 原始结论不会丢失。
 */
@Service
public class ReviewService {

    private final AnalysisReviewMapperExt queueMapper;
    private final AnalysisReviewMapper reviewMapper;
    private final CommentMapper commentMapper;
    private final VideoService videoService;
    private final GovernanceService governance;

    public ReviewService(AnalysisReviewMapperExt queueMapper, AnalysisReviewMapper reviewMapper,
                         CommentMapper commentMapper, VideoService videoService,
                         GovernanceService governance) {
        this.queueMapper = queueMapper;
        this.reviewMapper = reviewMapper;
        this.commentMapper = commentMapper;
        this.videoService = videoService;
        this.governance = governance;
    }

    /** 复核队列：默认低置信优先 */
    public Map<String, Object> queue(String videoId, String status, Boolean lowConfidence, int page, int size) {
        Long vid = videoService.resolveInternalId(videoId);
        if (vid == null) {
            return emptyPage(page, size);
        }
        governance.requireReadComments(vid);

        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 1);
        long total = queueMapper.countQueue(vid, status, lowConfidence);
        List<Map<String, Object>> rows = queueMapper.selectQueue(
                vid, status, lowConfidence, (safePage - 1) * safeSize, safeSize);

        // 把 JSON 字段展开成前端可直接渲染的结构
        for (Map<String, Object> r : rows) {
            Object topics = r.remove("topicsJson");
            Object pains = r.remove("painPointsJson");
            r.put("topics", parseArray(topics == null ? null : String.valueOf(topics)));
            r.put("painPoints", parseArray(pains == null ? null : String.valueOf(pains)));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", rows);
        out.put("total", total);
        out.put("page", safePage);
        out.put("size", safeSize);
        return out;
    }

    public Map<String, Object> stats(String videoId) {
        Long vid = videoService.resolveInternalId(videoId);
        Map<String, Object> out = new LinkedHashMap<>();
        if (vid == null) {
            return out;
        }
        governance.requireReadComments(vid);
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("PENDING", 0L);
        counts.put("APPROVED", 0L);
        counts.put("REJECTED", 0L);
        counts.put("CORRECTED", 0L);
        long total = 0;
        for (Map<String, Object> r : queueMapper.statsByVideo(vid)) {
            String s = String.valueOf(r.get("status"));
            long c = ((Number) r.get("count")).longValue();
            counts.merge(s, c, Long::sum);
            total += c;
        }
        out.put("counts", counts);
        out.put("total", total);
        out.put("reviewed", total - counts.getOrDefault("PENDING", 0L));
        return out;
    }

    @Transactional
    public Map<String, Object> submit(Long analysisId, String status, String note,
                                      Map<String, Object> corrections) {
        CommentAnalysis ca = queueMapper.selectById(analysisId);
        if (ca == null) {
            throw new IllegalArgumentException("分析结果不存在");
        }
        Comment comment = commentMapper.selectById(ca.getCommentId());
        if (comment == null) {
            throw new IllegalArgumentException("评论不存在");
        }
        governance.requireReadComments(comment.getVideoId());

        String st = status == null ? "" : status.trim().toUpperCase();
        if (!Arrays.asList("APPROVED", "REJECTED", "CORRECTED").contains(st)) {
            throw new IllegalArgumentException("复核状态必须是 APPROVED / REJECTED / CORRECTED");
        }

        JSONObject before = snapshot(ca);
        JSONObject after = before;
        String correctedJson = null;

        if ("CORRECTED".equals(st)) {
            if (corrections == null || corrections.isEmpty()) {
                throw new IllegalArgumentException("纠正必须至少提供一个字段");
            }
            applyCorrections(ca, corrections);
            after = snapshot(ca);
            JSONObject diff = new JSONObject();
            diff.put("before", before);
            diff.put("after", after);
            correctedJson = diff.toJSONString();

            ca.setReviewStatus("CORRECTED");
            ca.setReviewedBy(governance.currentUserId());
            ca.setReviewedAt(LocalDateTime.now());
            queueMapper.updateById(ca);
        } else {
            CommentAnalysis patch = new CommentAnalysis();
            patch.setId(ca.getId());
            patch.setReviewStatus(st);
            patch.setReviewedBy(governance.currentUserId());
            patch.setReviewedAt(LocalDateTime.now());
            queueMapper.updateById(patch);
        }

        AnalysisReview review = new AnalysisReview();
        review.setCommentAnalysisId(analysisId);
        review.setReviewerUserId(governance.currentUserId());
        review.setStatus(st);
        review.setNote(note == null ? null : truncate(note, 500));
        review.setCorrectedJson(correctedJson);
        reviewMapper.insert(review);

        governance.audit("REVIEW_ANALYSIS", "COMMENT_ANALYSIS", analysisId, st);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("analysisId", analysisId);
        out.put("reviewStatus", st);
        out.put("reviewedAt", LocalDateTime.now().toString());
        return out;
    }

    /** 单条评论的复核历史 */
    public List<Map<String, Object>> history(Long analysisId) {
        CommentAnalysis ca = queueMapper.selectById(analysisId);
        if (ca == null) {
            return Collections.emptyList();
        }
        Comment comment = commentMapper.selectById(ca.getCommentId());
        if (comment != null) {
            governance.requireReadComments(comment.getVideoId());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (AnalysisReview r : reviewMapper.selectList(new LambdaQueryWrapper<AnalysisReview>()
                .eq(AnalysisReview::getCommentAnalysisId, analysisId)
                .orderByDesc(AnalysisReview::getId).last("LIMIT 50"))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("status", r.getStatus());
            m.put("note", r.getNote());
            m.put("reviewerUserId", r.getReviewerUserId());
            m.put("createdAt", r.getCreatedAt());
            m.put("corrected", r.getCorrectedJson() == null ? null : JSON.parseObject(r.getCorrectedJson()));
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------------ 内部

    private void applyCorrections(CommentAnalysis ca, Map<String, Object> c) {
        if (c.get("sentiment") != null) {
            String s = String.valueOf(c.get("sentiment"));
            if (!Arrays.asList("positive", "neutral", "negative", "unknown").contains(s)) {
                throw new IllegalArgumentException("sentiment 取值非法");
            }
            ca.setSentiment(s);
        }
        if (c.get("intent") != null) {
            ca.setIntent(String.valueOf(c.get("intent")));
        }
        if (c.get("userNeed") != null) {
            ca.setUserNeed(truncate(String.valueOf(c.get("userNeed")), 500));
        }
        if (c.get("purchaseIntent") != null) {
            String p = String.valueOf(c.get("purchaseIntent"));
            if (!Arrays.asList("none", "low", "medium", "high").contains(p)) {
                throw new IllegalArgumentException("purchaseIntent 取值非法");
            }
            ca.setPurchaseIntent(p);
        }
        if (c.get("topics") != null) {
            ca.setTopicsJson(JSON.toJSONString(c.get("topics")));
        }
        if (c.get("painPoints") != null) {
            ca.setPainPointsJson(JSON.toJSONString(c.get("painPoints")));
        }
        // 人工修正后视为高置信，不再计入"待复核的低置信"集合
        ca.setIsUncertain(0);
    }

    private static JSONObject snapshot(CommentAnalysis ca) {
        JSONObject o = new JSONObject();
        o.put("sentiment", ca.getSentiment());
        o.put("intent", ca.getIntent());
        o.put("userNeed", ca.getUserNeed());
        o.put("purchaseIntent", ca.getPurchaseIntent());
        o.put("topics", parseArray(ca.getTopicsJson()));
        o.put("painPoints", parseArray(ca.getPainPointsJson()));
        return o;
    }

    private static List<String> parseArray(String json) {
        List<String> out = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return out;
        }
        try {
            for (Object o : JSON.parseArray(json)) {
                out.add(String.valueOf(o));
            }
        } catch (Exception ignored) {
            // 非数组则忽略
        }
        return out;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static Map<String, Object> emptyPage(int page, int size) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", Collections.emptyList());
        out.put("total", 0);
        out.put("page", page);
        out.put("size", size);
        return out;
    }
}
