package com.coolskyai.coolpilot.insight.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.coolskyai.coolpilot.insight.entity.Video;
import com.coolskyai.coolpilot.insight.mapper.OverviewMapper;
import com.coolskyai.coolpilot.insight.mapper.UsageRecordMapper;
import com.coolskyai.coolpilot.insight.mapper.VideoMapper;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 全局运营概览（跨视频），移植自 DYP 的 /overview + /stats + /trend。
 */
@Service
public class OverviewService {

    private final OverviewMapper overviewMapper;
    private final UsageRecordMapper usageMapper;
    private final VideoMapper videoMapper;
    private final VideoService videoService;
    private final GovernanceService governance;

    public OverviewService(OverviewMapper overviewMapper, UsageRecordMapper usageMapper,
                           VideoMapper videoMapper, VideoService videoService,
                           GovernanceService governance) {
        this.overviewMapper = overviewMapper;
        this.usageMapper = usageMapper;
        this.videoMapper = videoMapper;
        this.videoService = videoService;
        this.governance = governance;
    }

    public Map<String, Object> overview() {
        List<Long> ids = governance.readableVideoIds();
        if (ids != null && ids.isEmpty()) {
            return empty();
        }

        Map<String, Object> video = overviewMapper.videoSummary(ids);
        Map<String, Object> comment = overviewMapper.commentSummary(ids);
        Map<String, Object> analysis = overviewMapper.analysisSummary(ids);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("videos", num(video, "videos"));
        out.put("comments", num(comment, "comments"));
        out.put("users", num(comment, "users"));
        out.put("likes", num(comment, "likes"));
        out.put("analyzed", num(analysis, "analyzed"));
        out.put("pendingReview", num(analysis, "pendingReview"));
        out.put("approved", num(analysis, "approved"));
        out.put("corrected", num(analysis, "corrected"));

        // 逆序成时间正序，前端直接画折线
        List<Map<String, Object>> trend = new ArrayList<>(overviewMapper.dailyTrend(ids));
        Collections.reverse(trend);
        out.put("trend", trend);

        out.put("topVideos", overviewMapper.topVideos(ids));

        // 痛点聚合（JSON 数组展开计数）
        Map<String, Long> pain = new LinkedHashMap<>();
        for (String json : overviewMapper.painPointJsonList(ids)) {
            try {
                for (Object o : JSON.parseArray(json)) {
                    String key = String.valueOf(o);
                    if (!key.isEmpty()) {
                        pain.merge(key, 1L, Long::sum);
                    }
                }
            } catch (Exception ignored) {
                // 非数组忽略
            }
        }
        List<Map<String, Object>> painTop = new ArrayList<>();
        pain.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(12)
                .forEach(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", e.getKey());
                    m.put("value", e.getValue());
                    painTop.add(m);
                });
        out.put("painPointTop", painTop);

        out.put("todayTokens", usageMapper.todayTokens());
        out.put("todayCost", usageMapper.todayCost());
        return out;
    }

    /** 视频列表（供筛选下拉），附带每视频评论数，避免前端再查一次 */
    public List<Map<String, Object>> videoOptions() {
        return videoService.listForCurrentUser().stream().map(v -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("videoId", v.get("videoId"));
            m.put("title", v.get("title"));
            m.put("comments", v.get("comments"));
            return m;
        }).collect(java.util.stream.Collectors.toList());
    }

    private Map<String, Object> empty() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : new String[]{"videos", "comments", "users", "likes", "analyzed",
                "pendingReview", "approved", "corrected"}) {
            out.put(k, 0L);
        }
        out.put("trend", Collections.emptyList());
        out.put("topVideos", Collections.emptyList());
        out.put("painPointTop", Collections.emptyList());
        out.put("todayTokens", 0L);
        out.put("todayCost", 0);
        return out;
    }

    private static long num(Map<String, Object> row, String key) {
        if (row == null || row.get(key) == null) {
            return 0L;
        }
        return ((Number) row.get(key)).longValue();
    }
}
