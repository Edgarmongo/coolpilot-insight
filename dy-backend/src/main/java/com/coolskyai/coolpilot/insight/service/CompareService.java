package com.coolskyai.coolpilot.insight.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.coolskyai.coolpilot.insight.entity.Video;
import com.coolskyai.coolpilot.insight.entity.VideoInsight;
import com.coolskyai.coolpilot.insight.mapper.CommentMapper;
import com.coolskyai.coolpilot.insight.mapper.VideoInsightMapper;
import com.coolskyai.coolpilot.insight.mapper.VideoMapper;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 多视频对比与关键词级聚合。
 * 只做「已有结果的横向对齐」，不产生新的 AI 调用。
 */
@Service
public class CompareService {

    private final VideoService videoService;
    private final VideoMapper videoMapper;
    private final VideoInsightMapper insightMapper;
    private final CommentMapper commentMapper;
    private final GovernanceService governance;

    public CompareService(VideoService videoService, VideoMapper videoMapper,
                          VideoInsightMapper insightMapper, CommentMapper commentMapper,
                          GovernanceService governance) {
        this.videoService = videoService;
        this.videoMapper = videoMapper;
        this.insightMapper = insightMapper;
        this.commentMapper = commentMapper;
        this.governance = governance;
    }

    /** 并排对比 N 个视频：基础指标 + 需求/痛点 Top */
    public Map<String, Object> compare(List<String> videoIds) {
        if (videoIds == null || videoIds.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个视频");
        }
        if (videoIds.size() > 10) {
            throw new IllegalArgumentException("单次最多对比 10 个视频");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String raw : videoIds) {
            Long vid = videoService.resolveInternalId(raw);
            if (vid == null) {
                continue;
            }
            governance.requireReadComments(vid);
            Video v = videoMapper.selectById(vid);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("videoId", raw);
            row.put("title", v == null ? "" : v.getTitle());

            Map<String, Object> sum = commentMapper.summaryRow(vid);
            row.put("totalComments", sum == null ? 0 : sum.get("total"));
            row.put("totalLikes", sum == null ? 0 : sum.get("likes"));

            VideoInsight insight = insightMapper.selectOne(new LambdaQueryWrapper<VideoInsight>()
                    .eq(VideoInsight::getVideoId, vid)
                    .orderByDesc(VideoInsight::getId).last("LIMIT 1"));
            if (insight == null) {
                row.put("hasInsight", false);
                row.put("needs", Collections.emptyList());
                row.put("painPoints", Collections.emptyList());
                row.put("summary", "");
            } else {
                JSONObject data = JSON.parseObject(insight.getResultJson());
                row.put("hasInsight", true);
                row.put("needs", titles(data.getJSONArray("needs")));
                row.put("painPoints", titles(data.getJSONArray("pain_points")));
                row.put("summary", data.getString("summary"));
                row.put("purchaseIntent", data.getJSONObject("purchase_intent") == null
                        ? null : data.getJSONObject("purchase_intent").getString("level"));
            }
            rows.add(row);
        }

        // 共性痛点：出现在 2 个以上视频中的标签
        Map<String, Integer> painFreq = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            @SuppressWarnings("unchecked")
            List<String> pains = (List<String>) row.get("painPoints");
            for (String p : pains) {
                painFreq.merge(p, 1, Integer::sum);
            }
        }
        List<Map<String, Object>> commonPains = painFreq.entrySet().stream()
                .filter(e -> e.getValue() >= 2)
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", e.getKey());
                    m.put("videoCount", e.getValue());
                    return m;
                }).collect(Collectors.toList());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("videos", rows);
        out.put("commonPainPoints", commonPains);
        out.put("videoCount", rows.size());
        return out;
    }

    private static List<String> titles(JSONArray arr) {
        List<String> out = new ArrayList<>();
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.size(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String t = o == null ? null : o.getString("title");
            if (t != null && !t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }
}
