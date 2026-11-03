package com.coolskyai.coolpilot.insight.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.coolskyai.coolpilot.insight.config.AppProperties;
import com.coolskyai.coolpilot.insight.entity.AnalysisResult;
import com.coolskyai.coolpilot.insight.entity.Comment;
import com.coolskyai.coolpilot.insight.mapper.AnalysisResultMapper;
import com.coolskyai.coolpilot.insight.mapper.CommentMapper;
import com.coolskyai.coolpilot.insight.service.AnalysisService;
import com.coolskyai.coolpilot.insight.interceptor.RequestContext;
import com.coolskyai.coolpilot.insight.utils.MemoryCache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AnalysisServiceImpl implements AnalysisService {

    @Autowired
    private CommentMapper commentMapper;

    @Autowired
    private AnalysisResultMapper analysisResultMapper;

    @Autowired
    private MemoryCache cache;

    @Autowired
    private AppProperties appProperties;

    private static final String CACHE_PREFIX = "analysis:";
    private static final long CACHE_TTL = 30 * 60 * 1000L;

    private static BigDecimal calcPercent(long part, int total) {
        if (total <= 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(part * 100.0 / total).setScale(1, RoundingMode.HALF_UP);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<Map<String, Object>> parseCachedList(String json) {
        return (List) JSON.parseArray(json, Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseCachedMap(String json) {
        return JSON.parseObject(json, Map.class);
    }

    private static String getPeriod(java.time.LocalDateTime dt) {
        if (dt == null) return "未知";
        int h = dt.getHour();
        if (h < 6) return "00-06时";
        if (h < 12) return "06-12时";
        if (h < 18) return "12-18时";
        return "18-24时";
    }

    /** 如果非管理员，对 wrapper 追加 user_id 过滤 */
    private void applyUserFilter(LambdaQueryWrapper<Comment> wrapper) {
        Long userId = RequestContext.getUserId();
        Integer role = RequestContext.getRole();
        if (userId != null && (role == null || role != 1)) {
            wrapper.eq(Comment::getUserId, userId);
        }
    }

    @Override
    public Map<String, Object> getSummary(String videoId) {
        String cacheKey = CACHE_PREFIX + "summary:" + videoId;
        String cached = cache.get(cacheKey);
        if (cached != null) return parseCachedMap(cached);

        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Comment::getVideoId, videoId);
        applyUserFilter(wrapper);
        List<Comment> all = commentMapper.selectList(wrapper);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("videoId", videoId);
        summary.put("totalComments", all.size());
        summary.put("totalUsers", all.stream().map(Comment::getNickname).distinct().count());
        long totalLikes = all.stream().mapToLong(Comment::getLikes).sum();
        summary.put("totalLikes", totalLikes);
        summary.put("avgLikes", all.isEmpty() ? BigDecimal.ZERO
                : BigDecimal.valueOf(totalLikes).divide(BigDecimal.valueOf(all.size()), 2, RoundingMode.HALF_UP));

        cache.put(cacheKey, JSON.toJSONString(summary), CACHE_TTL);
        return summary;
    }

    @Override
    public List<Map<String, Object>> getRegionStats(String videoId) {
        String cacheKey = CACHE_PREFIX + "region:" + videoId;
        String cached = cache.get(cacheKey);
        if (cached != null) return parseCachedList(cached);

        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Comment::getVideoId, videoId);
        applyUserFilter(wrapper);
        List<Comment> all = commentMapper.selectList(wrapper);

        Map<String, Long> regionCount = all.stream()
                .collect(Collectors.groupingBy(Comment::getRegion, Collectors.counting()));
        int total = all.size();

        List<Map<String, Object>> result = regionCount.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("region", e.getKey());
                    item.put("count", e.getValue());
                    item.put("percentage", calcPercent(e.getValue(), total));
                    return item;
                }).collect(Collectors.toList());

        cache.put(cacheKey, JSON.toJSONString(result), CACHE_TTL);
        return result;
    }

    @Override
    public List<Map<String, Object>> getSentimentStats(String videoId) {
        String cacheKey = CACHE_PREFIX + "sentiment:" + videoId;
        String cached = cache.get(cacheKey);
        if (cached != null) return parseCachedList(cached);

        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Comment::getVideoId, videoId);
        applyUserFilter(wrapper);
        List<Comment> all = commentMapper.selectList(wrapper);

        long positive = all.stream().filter(c -> c.getSentiment() == 1).count();
        long neutral = all.stream().filter(c -> c.getSentiment() == 0).count();
        long negative = all.stream().filter(c -> c.getSentiment() == -1).count();
        int total = all.size();

        String[] labels = {"积极", "中性", "消极"};
        long[] values = {positive, neutral, negative};

        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sentiment", labels[i]);
            item.put("count", values[i]);
            item.put("percentage", calcPercent(values[i], total));
            result.add(item);
        }

        cache.put(cacheKey, JSON.toJSONString(result), CACHE_TTL);
        return result;
    }

    @Override
    public List<Map<String, Object>> getTrendStats(String videoId) {
        String cacheKey = CACHE_PREFIX + "trend:" + videoId;
        String cached = cache.get(cacheKey);
        if (cached != null) return parseCachedList(cached);

        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Comment::getVideoId, videoId);
        applyUserFilter(wrapper);
        List<Comment> all = commentMapper.selectList(wrapper);

        Map<LocalDate, Long> dailyCount = all.stream()
                .collect(Collectors.groupingBy(c -> c.getPublishTime().toLocalDate(), TreeMap::new, Collectors.counting()));

        List<Map<String, Object>> result = dailyCount.entrySet().stream()
                .map(e -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("date", e.getKey().toString());
                    item.put("count", e.getValue());
                    return item;
                }).collect(Collectors.toList());

        cache.put(cacheKey, JSON.toJSONString(result), CACHE_TTL);
        return result;
    }

    @Override
    public List<Map<String, Object>> getTopComments(String videoId) {
        String cacheKey = CACHE_PREFIX + "top:" + videoId;
        String cached = cache.get(cacheKey);
        if (cached != null) return parseCachedList(cached);

        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Comment::getVideoId, videoId);
        applyUserFilter(wrapper);
        wrapper.orderByDesc(Comment::getLikes);
        wrapper.last("LIMIT 20");
        List<Comment> topList = commentMapper.selectList(wrapper);

        List<Map<String, Object>> result = topList.stream().map(c -> buildTopCommentItem(c))
                .collect(Collectors.toList());

        cache.put(cacheKey, JSON.toJSONString(result), CACHE_TTL);
        return result;
    }

    /**
     * 构建高赞评论行数据。
     * getTopComments 与 refreshAnalysis 两处共用，避免字段不一致。
     */
    private static Map<String, Object> buildTopCommentItem(Comment c) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("douyinId", c.getDouyinId() != null ? c.getDouyinId() : "");
        item.put("nickname", c.getNickname());
        item.put("content", c.getContent());
        item.put("likes", c.getLikes());
        item.put("region", c.getRegion());
        item.put("publishTime", c.getPublishTime() != null ? c.getPublishTime().toString() : "");
        return item;
    }

    @Override
    public List<Map<String, Object>> getVideoList() {
        Long userId = RequestContext.getUserId();
        Integer role = RequestContext.getRole();

        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<>();
        if (userId != null && (role == null || role != 1)) {
            wrapper.eq(Comment::getUserId, userId);
        }
        List<Comment> all = commentMapper.selectList(wrapper);

        // 从 comment 表提取去重 videoIds，附带评论数
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        Map<String, Long> countMap = new LinkedHashMap<>();
        for (Comment c : all) {
            if (!seen.contains(c.getVideoId())) seen.add(c.getVideoId());
            countMap.merge(c.getVideoId(), 1L, Long::sum);
        }

        // 从 analysis_result 获取 analyzedAt（全局缓存，仅用作时间展示）
        List<AnalysisResult> results = analysisResultMapper.selectList(
                new LambdaQueryWrapper<AnalysisResult>().orderByDesc(AnalysisResult::getAnalyzedAt));
        Map<String, String> analyzedMap = new LinkedHashMap<>();
        for (AnalysisResult r : results) {
            if (!analyzedMap.containsKey(r.getVideoId())) {
                analyzedMap.put(r.getVideoId(), r.getAnalyzedAt() != null ? r.getAnalyzedAt().toString() : "");
            }
        }

        List<Map<String, Object>> list = new ArrayList<>();
        for (String vid : seen) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("videoId", vid);
            item.put("totalComments", countMap.getOrDefault(vid, 0L));
            item.put("analyzedAt", analyzedMap.getOrDefault(vid, ""));
            list.add(item);
        }
        return list;
    }

    @Override
    public List<Map<String, Object>> getWordCloud(String videoId) {
        String cacheKey = CACHE_PREFIX + "wordcloud:" + videoId;
        String cached = cache.get(cacheKey);
        if (cached != null) return parseCachedList(cached);

        List<Map<String, Object>> result = new ArrayList<>();
        try {
            java.net.URL url = new java.net.URL(appProperties.getFlaskBaseUrl() + "/wordcloud?video_id=" + videoId
                    + "&user_id=" + (RequestContext.getUserId() != null && (RequestContext.getRole() == null || RequestContext.getRole() != 1) ? RequestContext.getUserId() : ""));
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000); conn.setReadTimeout(30000);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String l; while ((l = r.readLine()) != null) sb.append(l);
            }
            conn.disconnect();
            String json = sb.toString();
            if (json.startsWith("[")) { result = parseCachedList(json); }
        } catch (Exception e) {
            result = new ArrayList<>();
        }
        cache.put(cacheKey, JSON.toJSONString(result), CACHE_TTL);
        return result;
    }

    // 直辖市
    private static final Set<String> ZHIXIASHI = new HashSet<>(Arrays.asList("北京","天津","上海","重庆"));
    // 自治区
    private static final Set<String> ZIZHIQU = new HashSet<>(Arrays.asList("内蒙古","广西","西藏","宁夏","新疆"));
    // 23 省（含台湾）
    private static final Set<String> PROVINCES_23 = new HashSet<>(Arrays.asList(
        "河北","山西","辽宁","吉林","黑龙江","江苏","浙江","安徽","福建","江西",
        "山东","河南","湖北","湖南","广东","海南","四川","贵州","云南","陕西",
        "甘肃","青海","台湾"
    ));

    private static String normalizeRegion(String r) {
        String s = r.replace("省","").replace("市","").replace("自治区","")
                    .replace("壮族","").replace("回族","").replace("维吾尔","")
                    .replace("特别行政区","").trim();
        if (s.startsWith("中国")) s = s.substring(2);
        // 港澳台 → 统一归为 港澳台
        if (s.equals("香港") || s.equals("澳门") || s.equals("台湾")
            || s.equals("中国香港") || s.equals("中国澳门") || s.equals("中国台湾")) return "港澳台";
        if (ZHIXIASHI.contains(s)) return s;
        if (ZIZHIQU.contains(s)) return s;
        if (PROVINCES_23.contains(s)) return s;
        return "其他";
    }

    @Override
    public String getTopic(String videoId) {
        String cacheKey = CACHE_PREFIX + "topic:" + videoId;
        String cached = cache.get(cacheKey);
        if (cached != null) return cached;

        // 查询评论
        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Comment::getVideoId, videoId);
        applyUserFilter(wrapper);
        List<Comment> all = commentMapper.selectList(wrapper);
        if (all.isEmpty()) return "";

        // 计算 5 维度结构化数据
        Map<String, Object> dims = computeTopicDims(all, videoId);

        // 调用 AI Agent
        String agentReport = callTopicAgent(dims);
        if (agentReport != null) {
            cache.put(cacheKey, agentReport, CACHE_TTL);
            return agentReport;
        }

        // 降级：AI 不可用时用旧版逻辑
        System.out.println("[topic] Agent unavailable, using fallback for " + videoId);
        String fallback = buildFallbackReport(dims);
        cache.put(cacheKey, fallback, CACHE_TTL);
        return fallback;
    }

    // ====================== AI Agent ======================

    /** 将结构化数据 POST 给 Python Flask Agent，由其调用 DeepSeek 生成报告 */
    @SuppressWarnings("unchecked")
    private String callTopicAgent(Map<String, Object> dims) {
        try {
            java.net.URL url = new java.net.URL(appProperties.getFlaskBaseUrl() + "/analyze_topic");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(30000);
            conn.setDoOutput(true);

            String jsonBody = JSON.toJSONString(dims);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes("UTF-8"));
            }

            if (conn.getResponseCode() == 200) {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                    String l; while ((l = r.readLine()) != null) sb.append(l);
                }
                JSONObject resp = JSON.parseObject(sb.toString());
                if (resp.getIntValue("code") == 0) {
                    return resp.getString("data");
                }
            }
        } catch (Exception e) {
            System.out.println("[topic] Agent call failed: " + e.getMessage());
        }
        return null;
    }

    // ====================== 5 维度数据计算 ======================

    /** 从评论列表中提取 5 维度结构化数据，返回 Map 供 Agent 调用或降级报告使用 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> computeTopicDims(List<Comment> all, String videoId) {
        Map<String, Object> dims = new LinkedHashMap<>();
        dims.put("totalComments", all.size());
        dims.put("totalUsers", all.stream().map(Comment::getNickname).distinct().count());
        dims.put("totalLikes", all.stream().mapToLong(Comment::getLikes).sum());
        dims.put("videoId", videoId);

        // 维度1: 关键词
        List<Map<String, Object>> words = getWordCloud(videoId);
        List<String> topKw = new ArrayList<>();
        for (int i = 0; i < Math.min(15, words.size()); i++) {
            topKw.add((String) words.get(i).get("name"));
        }
        dims.put("keywords", words.stream().limit(15).collect(Collectors.toList()));

        // 维度2: 句子结构
        long qCount = 0, exCount = 0, stCount = 0, imCount = 0;
        for (Comment c : all) {
            String t = c.getContent();
            boolean isQuestion = t.contains("?") || t.indexOf("吗") >= 0 || t.indexOf("呢") >= 0 || t.indexOf("怎么") >= 0 || t.indexOf("如何") >= 0;
            boolean isExclaim = t.contains("!") || (t.indexOf("太") >= 0 && t.length() < 30);
            boolean isImper = t.indexOf("建议") >= 0 || t.indexOf("应该") >= 0 || t.indexOf("推荐") >= 0;
            if (isQuestion) qCount++;
            if (isExclaim) exCount++;
            if (isImper) imCount++;
        }
        stCount = all.size() - qCount - exCount - imCount;
        if (stCount < 0) stCount = 0;
        Map<String, Object> ss = new LinkedHashMap<>();
        ss.put("question", qCount); ss.put("exclamation", exCount);
        ss.put("statement", stCount); ss.put("imperative", imCount);
        dims.put("sentenceStructure", ss);

        // 维度3: 情感语境
        List<Map<String, Object>> sentiments = getSentimentStats(videoId);
        dims.put("sentimentDistribution", sentiments);
        String mainSent = "中性"; long mainPct = 0;
        for (Map<String, Object> s : sentiments) {
            long pct = ((BigDecimal) s.get("percentage")).longValue();
            if (pct > mainPct) { mainPct = pct; mainSent = (String) s.get("sentiment"); }
        }
        dims.put("dominantSentiment", mainSent);
        all.sort(Comparator.comparing(Comment::getPublishTime));
        int mid = all.size() / 2;
        long earlyPos = mid > 0 ? all.subList(0, mid).stream().filter(c -> c.getSentiment() == 1).count() : 0;
        long latePos = all.subList(mid, all.size()).stream().filter(c -> c.getSentiment() == 1).count();
        String trendDesc = "";
        if (latePos > earlyPos + mid * 0.1) { trendDesc = "随着讨论深入，正面评价逐渐增多"; }
        else if (earlyPos > latePos + mid * 0.1) { trendDesc = "后期讨论中出现更多不同声音"; }
        dims.put("sentimentTrendDesc", trendDesc);

        // 维度4: 关键词共现 → 话题发现
        Map<String, Long> coocMap = new LinkedHashMap<>();
        int kwSize = Math.min(topKw.size(), 10);
        for (int i = 0; i < kwSize; i++) {
            for (int j = i + 1; j < kwSize; j++) {
                long pairCnt = 0;
                for (Comment c : all) {
                    if (c.getContent().contains(topKw.get(i)) && c.getContent().contains(topKw.get(j))) {
                        pairCnt++;
                    }
                }
                if (pairCnt >= 3) { coocMap.put(topKw.get(i) + "+" + topKw.get(j), pairCnt); }
            }
        }

        int total = all.size();
        List<Map<String, Object>> topicDetails = new ArrayList<>();
        for (String pair : coocMap.keySet()) {
            String[] parts = pair.split("\\+");
            String w1 = parts[0], w2 = parts[1];
            long pos = 0, neg = 0, neu = 0;
            for (Comment c : all) {
                if (c.getContent().contains(w1) && c.getContent().contains(w2)) {
                    if (c.getSentiment() == 1) pos++;
                    else if (c.getSentiment() == -1) neg++;
                    else neu++;
                }
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("topic", w1 + "·" + w2);
            detail.put("total", pos + neg + neu);
            detail.put("positive", pos);
            detail.put("negative", neg);
            detail.put("neutral", neu);
            topicDetails.add(detail);
        }
        topicDetails.sort((a, b) -> Long.compare((Long) b.get("total"), (Long) a.get("total")));
        while (topicDetails.size() > 5) { topicDetails.remove(topicDetails.size() - 1); }
        dims.put("topics", topicDetails);

        // 风险 & 亮点
        List<Map<String, Object>> riskTopics = new ArrayList<>();
        List<Map<String, Object>> goodTopics = new ArrayList<>();
        for (Map<String, Object> td : topicDetails) {
            long t = (Long) td.get("total");
            long n = (Long) td.get("negative");
            long p = (Long) td.get("positive");
            long negPct = t > 0 ? n * 100 / t : 0;
            long posPct = t > 0 ? p * 100 / t : 0;
            if (negPct >= 40) riskTopics.add(td);
            else if (posPct >= 50) goodTopics.add(td);
        }
        dims.put("riskTopics", riskTopics);
        dims.put("goodTopics", goodTopics);

        // 维度5: 动机
        long likeCount = 0, askCount = 0, shareCount = 0, complainCount = 0;
        for (Comment c : all) {
            String t = c.getContent();
            if (t.contains("喜欢") || t.contains("可爱") || t.contains("好看") || t.contains("好") || t.contains("爱") || t.contains("棒")) { likeCount++; }
            if (t.contains("怎么") || t.contains("请问") || t.contains("问一下") || t.contains("谁懂")) { askCount++; }
            if (t.contains("我家") || t.contains("以前") || t.contains("小时候") || t.contains("养过") || (t.contains("我") && t.length() > 30)) { shareCount++; }
            if (t.contains("但是") || t.contains("可是") || t.contains("为啥") || t.contains("为什么") || t.contains("不如") || t.contains("搞笑") || t.contains("笑死")) { complainCount++; }
        }
        long motiveMax = Math.max(Math.max(likeCount, askCount), Math.max(shareCount, complainCount));
        String mainMotive = "表达喜爱";
        if (askCount == motiveMax) { mainMotive = "提问求解"; }
        else if (shareCount == motiveMax) { mainMotive = "分享经历"; }
        else if (complainCount == motiveMax) { mainMotive = "幽默调侃"; }
        dims.put("dominantMotivation", mainMotive);
        Map<String, Object> motMap = new LinkedHashMap<>();
        motMap.put("表达喜爱", likeCount); motMap.put("提问求解", askCount);
        motMap.put("分享经历", shareCount); motMap.put("幽默调侃", complainCount);
        dims.put("motivations", motMap);

        // 地区 & 时间
        List<Map<String, Object>> regions = getRegionStats(videoId);
        List<String> topRegions = new ArrayList<>();
        for (int i = 0; i < Math.min(5, regions.size()); i++) { topRegions.add((String) regions.get(i).get("region")); }
        dims.put("topRegions", topRegions);
        List<Map<String, Object>> trends = getTrendStats(videoId);
        String peakDay = ""; long peakCnt = 0;
        for (Map<String, Object> t : trends) {
            long cnt = ((Number) t.get("count")).longValue();
            if (cnt > peakCnt) { peakCnt = cnt; peakDay = (String) t.get("date"); }
        }
        dims.put("peakDay", peakDay);
        dims.put("peakCount", peakCnt);

        // 好评率
        long totalPos = all.stream().filter(c -> c.getSentiment() == 1).count();
        dims.put("posPctAll", all.size() > 0 ? totalPos * 100 / all.size() : 0);

        return dims;
    }

    // ====================== 降级报告 ======================

    /** AI Agent 不可用时，用纯统计逻辑拼报告 */
    @SuppressWarnings("unchecked")
    private String buildFallbackReport(Map<String, Object> dims) {
        int totalComments = (int) dims.get("totalComments");
        long posPctAll = ((Number) dims.getOrDefault("posPctAll", 0)).longValue();
        String mainSent = (String) dims.getOrDefault("dominantSentiment", "中性");
        String peakDay = (String) dims.getOrDefault("peakDay", "");
        long peakCnt = ((Number) dims.getOrDefault("peakCount", 0)).longValue();
        List<String> topKw = ((List<Map<String, Object>>) dims.get("keywords")).stream()
                .map(m -> (String) m.get("name")).collect(Collectors.toList());
        List<String> topRegions = (List<String>) dims.getOrDefault("topRegions", new ArrayList<>());
        List<Map<String, Object>> riskTopics = (List<Map<String, Object>>) dims.getOrDefault("riskTopics", new ArrayList<>());
        List<Map<String, Object>> goodTopics = (List<Map<String, Object>>) dims.getOrDefault("goodTopics", new ArrayList<>());
        List<Map<String, Object>> topicDetails = (List<Map<String, Object>>) dims.getOrDefault("topics", new ArrayList<>());

        StringBuilder sb = new StringBuilder();

        sb.append("(👉ﾟヮﾟ)👉关键结论\n");
        sb.append("- 整体表现：共").append(totalComments).append("条评论，好评率").append(posPctAll).append("%，情感以").append(mainSent).append("为主");
        if (!peakDay.isEmpty()) { sb.append("，讨论热度峰值出现在").append(peakDay).append("（").append(peakCnt).append("条）"); }
        sb.append("\n");

        if (!goodTopics.isEmpty()) {
            sb.append("- 核心亮点：");
            for (int i = 0; i < goodTopics.size(); i++) {
                if (i > 0) sb.append("、");
                sb.append(goodTopics.get(i).get("topic"));
            }
            sb.append("相关话题讨论整体偏向积极，用户好感度较好\n");
        }

        if (!riskTopics.isEmpty()) {
            sb.append("- 风险预警：**");
            for (int i = 0; i < riskTopics.size(); i++) {
                if (i > 0) sb.append("、");
                Map<String, Object> rt = riskTopics.get(i);
                long negPct = ((Long) rt.get("total") > 0) ? (Long) rt.get("negative") * 100 / (Long) rt.get("total") : 0;
                sb.append(rt.get("topic")).append("相关评论负面占比高达").append(negPct).append("%，争议性极强，需优先优化**\n");
            }
        }

        if (!topicDetails.isEmpty()) {
            sb.append("\n(👉ﾟヮﾟ)👉 话题数据明细\n");
            for (int i = 0; i < topicDetails.size(); i++) {
                Map<String, Object> td = topicDetails.get(i);
                String topic = (String) td.get("topic");
                long tTotal = (Long) td.get("total");
                long tPos = (Long) td.get("positive");
                long tNeg = (Long) td.get("negative");
                long tPosPct = tTotal > 0 ? tPos * 100 / tTotal : 0;
                long tNegPct = tTotal > 0 ? tNeg * 100 / tTotal : 0;
                boolean isRisk = tNegPct >= 40;

                sb.append(i + 1).append(". **").append(topic).append("**");
                if (isRisk) sb.append("（重点风险）");
                sb.append("：").append(tTotal).append("条相关评论，正面占").append(tPosPct).append("%，负面占").append(tNegPct).append("%");
                if (isRisk) {
                    sb.append("，用户争议强烈，建议重点复盘该环节内容");
                } else if (tPosPct >= 50) {
                    sb.append("，整体讨论氛围偏向积极");
                } else {
                    sb.append("，讨论氛围较为中性");
                }
                sb.append("\n");
            }
        }

        sb.append("\n【总结】该视频的核心讨论围绕");
        if (!topKw.isEmpty()) {
            int n = Math.min(3, topKw.size());
            sb.append(String.join("、", topKw.subList(0, n)));
        }
        sb.append("相关话题展开，观众主要来自");
        if (!topRegions.isEmpty()) {
            int n = Math.min(3, topRegions.size());
            sb.append(String.join("、", topRegions.subList(0, n)));
        }
        sb.append("等地区，整体实现了用户情感共鸣与话题讨论的目标。");
        if (!riskTopics.isEmpty()) {
            sb.append("其中「");
            sb.append(riskTopics.get(0).get("topic"));
            sb.append("」相关内容是当前的主要争议点，需针对性优化以降低负面反馈。");
        }

        return sb.toString();
    }

    @Override
    public Map<String, Object> getSankey(String videoId) {
        String cacheKey = CACHE_PREFIX + "sankey:" + videoId;
        String cached = cache.get(cacheKey);
        if (cached != null) return parseCachedMap(cached);

        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Comment::getVideoId, videoId);
        applyUserFilter(wrapper);
        List<Comment> all = commentMapper.selectList(wrapper);

        // 统计: "hour|sentiment|region" → likes sum
        Map<String, Long> linkMap = new LinkedHashMap<>();
        Set<String> periodSet = new LinkedHashSet<>();
        Set<String> regionSet = new LinkedHashSet<>();

        for (Comment c : all) {
            String period = c.getPublishTime() != null ?
                String.format("%02d:00~%02d:00", c.getPublishTime().getHour(), (c.getPublishTime().getHour() + 1) % 24) : "未知";
            String sent = c.getSentiment() == 1 ? "积极" : c.getSentiment() == -1 ? "消极" : "中性";
            String region = normalizeRegion(c.getRegion());
            periodSet.add(period);
            regionSet.add(region);
            String key = period + "|" + sent + "|" + region;
            linkMap.put(key, linkMap.getOrDefault(key, 0L) + c.getLikes());
        }

        // 节点: 时段 → 情感 → 地区
        List<String> periodOrdered = new ArrayList<>(periodSet);
        Collections.sort(periodOrdered);
        List<String> sentOrdered = Arrays.asList("积极", "中性", "消极");
        List<String> regionOrdered = new ArrayList<>(regionSet);
        Collections.sort(regionOrdered);

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (String s : periodOrdered) { Map<String,Object> m = new LinkedHashMap<>(); m.put("name",s); nodes.add(m); }
        for (String s : sentOrdered) { Map<String,Object> m = new LinkedHashMap<>(); m.put("name",s); nodes.add(m); }
        for (String s : regionOrdered) { Map<String,Object> m = new LinkedHashMap<>(); m.put("name",s); nodes.add(m); }

        // 链接: period → sentiment → region
        List<Map<String, Object>> links = new ArrayList<>();
        // period → sentiment
        Map<String, Long> psMap = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : linkMap.entrySet()) {
            String[] parts = e.getKey().split("\\|");
            String psKey = parts[0] + "|" + parts[1];
            psMap.put(psKey, psMap.getOrDefault(psKey, 0L) + e.getValue());
        }
        for (Map.Entry<String, Long> e : psMap.entrySet()) {
            String[] parts = e.getKey().split("\\|");
            Map<String,Object> l = new LinkedHashMap<>(); l.put("source",parts[0]); l.put("target",parts[1]); l.put("value",e.getValue()); links.add(l);
        }
        // sentiment → region
        Map<String, Long> srMap = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : linkMap.entrySet()) {
            String[] parts = e.getKey().split("\\|");
            String srKey = parts[1] + "|" + parts[2];
            srMap.put(srKey, srMap.getOrDefault(srKey, 0L) + e.getValue());
        }
        for (Map.Entry<String, Long> e : srMap.entrySet()) {
            String[] parts = e.getKey().split("\\|");
            Map<String,Object> l = new LinkedHashMap<>(); l.put("source",parts[0]); l.put("target",parts[1]); l.put("value",e.getValue()); links.add(l);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", nodes);
        result.put("links", links);

        cache.put(cacheKey, JSON.toJSONString(result), CACHE_TTL);
        return result;
    }

    @Override
    public AnalysisResult refreshAnalysis(String videoId) {
        cache.deleteByPrefix(CACHE_PREFIX);

        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Comment::getVideoId, videoId);
        applyUserFilter(wrapper);
        List<Comment> all = commentMapper.selectList(wrapper);
        if (all.isEmpty()) return null;

        int totalComments = all.size();
        long totalUsers = all.stream().map(Comment::getNickname).distinct().count();
        long totalLikes = all.stream().mapToLong(Comment::getLikes).sum();
        BigDecimal avgLikes = BigDecimal.valueOf(totalLikes)
                .divide(BigDecimal.valueOf(totalComments), 2, RoundingMode.HALF_UP);

        Map<String, Long> regionCount = all.stream()
                .collect(Collectors.groupingBy(Comment::getRegion, Collectors.counting()));
        List<Map<String, Object>> regionList = regionCount.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("region", e.getKey());
                    item.put("count", e.getValue());
                    item.put("percentage", calcPercent(e.getValue(), totalComments));
                    return item;
                }).collect(Collectors.toList());

        long positive = all.stream().filter(c -> c.getSentiment() == 1).count();
        long neutral = all.stream().filter(c -> c.getSentiment() == 0).count();
        long negative = all.stream().filter(c -> c.getSentiment() == -1).count();

        List<Map<String, Object>> sentimentList = new ArrayList<>();
        Map<String, Object> pos = new LinkedHashMap<>(); pos.put("sentiment", "积极"); pos.put("count", positive); pos.put("percentage", calcPercent(positive, totalComments)); sentimentList.add(pos);
        Map<String, Object> neu = new LinkedHashMap<>(); neu.put("sentiment", "中性"); neu.put("count", neutral); neu.put("percentage", calcPercent(neutral, totalComments)); sentimentList.add(neu);
        Map<String, Object> neg = new LinkedHashMap<>(); neg.put("sentiment", "消极"); neg.put("count", negative); neg.put("percentage", calcPercent(negative, totalComments)); sentimentList.add(neg);

        Map<LocalDate, Long> dailyCount = all.stream()
                .collect(Collectors.groupingBy(c -> c.getPublishTime().toLocalDate(), TreeMap::new, Collectors.counting()));
        List<Map<String, Object>> dailyList = dailyCount.entrySet().stream()
                .map(e -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("date", e.getKey().toString());
                    item.put("count", e.getValue());
                    return item;
                }).collect(Collectors.toList());

        List<Map<String, Object>> topList = all.stream()
                .sorted(Comparator.comparingInt(Comment::getLikes).reversed()).limit(20)
                .map(c -> buildTopCommentItem(c))
                .collect(Collectors.toList());

        LambdaQueryWrapper<AnalysisResult> arWrapper = new LambdaQueryWrapper<>();
        arWrapper.eq(AnalysisResult::getVideoId, videoId);
        AnalysisResult ar = analysisResultMapper.selectOne(arWrapper);
        if (ar == null) { ar = new AnalysisResult(); ar.setVideoId(videoId); }
        ar.setTotalComments(totalComments);
        ar.setTotalUsers((int) totalUsers);
        ar.setTotalLikes(totalLikes);
        ar.setAvgLikes(avgLikes);
        ar.setRegionStats(JSON.toJSONString(regionList));
        ar.setSentimentStats(JSON.toJSONString(sentimentList));
        ar.setDailyStats(JSON.toJSONString(dailyList));
        ar.setTopComments(JSON.toJSONString(topList));
        ar.setAnalyzedAt(LocalDateTime.now());
        if (ar.getId() != null) analysisResultMapper.updateById(ar);
        else analysisResultMapper.insert(ar);

        Map<String, Object> summaryMap = new LinkedHashMap<>();
        summaryMap.put("videoId", videoId);
        summaryMap.put("totalComments", totalComments);
        summaryMap.put("totalUsers", totalUsers);
        summaryMap.put("totalLikes", totalLikes);
        summaryMap.put("avgLikes", avgLikes);
        cache.put(CACHE_PREFIX + "summary:" + videoId, JSON.toJSONString(summaryMap), CACHE_TTL);
        cache.put(CACHE_PREFIX + "region:" + videoId, JSON.toJSONString(regionList), CACHE_TTL);
        cache.put(CACHE_PREFIX + "sentiment:" + videoId, JSON.toJSONString(sentimentList), CACHE_TTL);
        cache.put(CACHE_PREFIX + "trend:" + videoId, JSON.toJSONString(dailyList), CACHE_TTL);
        cache.put(CACHE_PREFIX + "top:" + videoId, JSON.toJSONString(topList), CACHE_TTL);

        return ar;
    }
}
