package com.coolskyai.coolpilot.insight.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.coolskyai.coolpilot.insight.config.AppProperties;
import com.coolskyai.coolpilot.insight.mapper.CommentMapper;
import com.coolskyai.coolpilot.insight.mapper.VideoMapper;
import com.coolskyai.coolpilot.insight.service.AnalysisService;
import com.coolskyai.coolpilot.insight.service.GovernanceService;
import com.coolskyai.coolpilot.insight.service.VideoService;
import com.coolskyai.coolpilot.insight.utils.MemoryCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 分析服务。所有方法入参仍为对外 videoId（aweme_id），内部换算为 video.id。
 * 统计口径全部来自 comment / comment_analysis 事实表，不再使用 analysis_result 快照。
 */
@Service
public class AnalysisServiceImpl implements AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisServiceImpl.class);
    private static final String CACHE_PREFIX = "analysis:";
    private static final long CACHE_TTL = 5 * 60 * 1000L;

    @Autowired
    private CommentMapper commentMapper;
    @Autowired
    private VideoMapper videoMapper;
    @Autowired
    private VideoService videoService;
    @Autowired
    private GovernanceService governance;
    @Autowired
    private MemoryCache cache;
    @Autowired
    private AppProperties appProperties;

    // ------------------------------------------------------------ 工具

    private static BigDecimal pct(long part, long total) {
        if (total <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(part * 100.0 / total).setScale(1, RoundingMode.HALF_UP);
    }

    private Long requireVideoId(String videoId) {
        Long id = videoService.resolveInternalId(videoId);
        if (id == null) {
            return null;
        }
        return id;
    }

    private static String sentimentZh(String raw) {
        if (raw == null) {
            return "中性";
        }
        switch (raw) {
            case "positive":
                return "积极";
            case "negative":
                return "消极";
            default:
                return "中性";
        }
    }

    private static String normalizeRegion(String r) {
        if (r == null || r.trim().isEmpty()) {
            return "未知";
        }
        String s = r.trim();
        for (String suffix : new String[]{"省", "市", "自治区", "特别行政区"}) {
            if (s.endsWith(suffix) && s.length() > suffix.length()) {
                if ("自治区".equals(suffix)) {
                    // 内蒙古自治区 → 内蒙古
                    s = s.substring(0, s.length() - suffix.length());
                } else if ("特别行政区".equals(suffix)) {
                    s = s.substring(0, s.length() - suffix.length());
                } else {
                    s = s.substring(0, s.length() - suffix.length());
                }
                break;
            }
        }
        // 广西壮族自治区 / 新疆维吾尔自治区 / 宁夏回族自治区
        if (s.endsWith("壮族自治区")) return "广西";
        if (s.endsWith("维吾尔自治区")) return "新疆";
        if (s.endsWith("回族自治区")) return "宁夏";
        if (s.endsWith("自治区")) return s.substring(0, s.length() - 3);
        if ("内蒙古".equals(s) || "广西".equals(s) || "西藏".equals(s) || "宁夏".equals(s) || "新疆".equals(s)) {
            return s;
        }
        return s;
    }

    // ------------------------------------------------------------ 概览

    @Override
    public Map<String, Object> getSummary(String videoId) {
        Long vid = requireVideoId(videoId);
        if (vid == null) {
            return emptySummary(videoId);
        }
        governance.requireReadComments(vid);

        Map<String, Object> row = commentMapper.summaryRow(vid);
        long total = row == null || row.get("total") == null ? 0L : ((Number) row.get("total")).longValue();
        long users = row == null || row.get("users") == null ? 0L : ((Number) row.get("users")).longValue();
        long likes = row == null || row.get("likes") == null ? 0L : ((Number) row.get("likes")).longValue();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("videoId", videoId);
        out.put("totalComments", total);
        out.put("totalUsers", users);
        out.put("totalLikes", likes);
        out.put("avgLikes", total == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(likes * 1.0 / total).setScale(2, RoundingMode.HALF_UP));
        com.coolskyai.coolpilot.insight.entity.Video v = videoMapper.selectById(vid);
        out.put("lastCollectedAt", v == null ? null : v.getLastCollectedAt());
        out.put("analyzedAt", null);
        return out;
    }

    private Map<String, Object> emptySummary(String videoId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("videoId", videoId);
        out.put("totalComments", 0);
        out.put("totalUsers", 0);
        out.put("totalLikes", 0);
        out.put("avgLikes", BigDecimal.ZERO);
        return out;
    }

    // ------------------------------------------------------------ 地区

    @Override
    public List<Map<String, Object>> getRegionStats(String videoId) {
        Long vid = requireVideoId(videoId);
        if (vid == null) {
            return Collections.emptyList();
        }
        governance.requireReadComments(vid);

        List<Map<String, Object>> rows = commentMapper.regionStats(vid);
        long total = rows.stream().mapToLong(r -> ((Number) r.get("count")).longValue()).sum();

        // 合并同义地区（如 "辽宁" 与 "辽宁省"）
        Map<String, Long> merged = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String name = normalizeRegion(String.valueOf(r.get("region")));
            long c = ((Number) r.get("count")).longValue();
            merged.merge(name, c, Long::sum);
        }
        List<Map<String, Object>> out = new ArrayList<>(merged.size());
        merged.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("region", e.getKey());
                    m.put("count", e.getValue());
                    m.put("percentage", pct(e.getValue(), total));
                    out.add(m);
                });
        return out;
    }

    // ------------------------------------------------------------ 情感

    @Override
    public List<Map<String, Object>> getSentimentStats(String videoId) {
        Long vid = requireVideoId(videoId);
        if (vid == null) {
            return Collections.emptyList();
        }
        governance.requireReadComments(vid);

        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("积极", 0L);
        counts.put("中性", 0L);
        counts.put("消极", 0L);
        List<Map<String, Object>> rows = commentMapper.sentimentStats(vid);
        long total = 0;
        for (Map<String, Object> r : rows) {
            String zh = sentimentZh(String.valueOf(r.get("sentiment")));
            long c = ((Number) r.get("count")).longValue();
            counts.merge(zh, c, Long::sum);
            total += c;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sentiment", e.getKey());
            m.put("count", e.getValue());
            m.put("percentage", pct(e.getValue(), total));
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------ 趋势

    @Override
    public List<Map<String, Object>> getTrendStats(String videoId) {
        Long vid = requireVideoId(videoId);
        if (vid == null) {
            return Collections.emptyList();
        }
        governance.requireReadComments(vid);
        List<Map<String, Object>> rows = commentMapper.dailyStats(vid);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", String.valueOf(r.get("date")));
            m.put("count", ((Number) r.get("count")).longValue());
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------ 高赞

    @Override
    public List<Map<String, Object>> getTopComments(String videoId) {
        Long vid = requireVideoId(videoId);
        if (vid == null) {
            return Collections.emptyList();
        }
        governance.requireReadComments(vid);
        List<Map<String, Object>> rows = commentMapper.topComments(vid, 20);
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("douyinId", r.get("douyinId") == null ? "" : r.get("douyinId"));
            m.put("nickname", r.get("nickname"));
            m.put("region", r.get("region"));
            m.put("content", r.get("content"));
            m.put("likes", ((Number) r.get("likes")).intValue());
            m.put("publishTime", r.get("publishTime") == null ? "" : r.get("publishTime").toString());
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------ 视频列表

    @Override
    public List<Map<String, Object>> getVideoList() {
        return videoService.listForCurrentUser();
    }

    // ------------------------------------------------------------ 词云

    @Override
    public List<Map<String, Object>> getWordCloud(String videoId) {
        Long vid = requireVideoId(videoId);
        if (vid == null) {
            return Collections.emptyList();
        }
        governance.requireReadComments(vid);
        String cacheKey = CACHE_PREFIX + "wordcloud:" + vid;
        String cached = cache.get(cacheKey);
        if (cached != null) {
            return toMapList(JSON.parseArray(cached));
        }

        List<Map<String, Object>> result = Collections.emptyList();
        try {
            String body = httpGet(appProperties.getFlaskBaseUrl() + "/wordcloud?video_id=" + vid);
            JSONObject resp = JSON.parseObject(body);
            if (resp != null && resp.getIntValue("code") == 0) {
                result = toMapList(JSON.parseArray(resp.getString("data")));
            }
        } catch (Exception e) {
            log.warn("[wordcloud] Flask 调用失败: {}", e.getMessage());
        }
        cache.put(cacheKey, JSON.toJSONString(result), CACHE_TTL);
        return result;
    }

    // ------------------------------------------------------------ 桑基

    @Override
    public Map<String, Object> getSankey(String videoId) {
        Long vid = requireVideoId(videoId);
        if (vid == null) {
            return Collections.emptyMap();
        }
        governance.requireReadComments(vid);

        String cacheKey = CACHE_PREFIX + "sankey:" + vid;
        String cached = cache.get(cacheKey);
        if (cached != null) {
            return JSON.parseObject(cached);
        }

        List<Map<String, Object>> rows = commentMapper.commentRowsWithSentiment(vid);
        Map<String, Long> linkMap = new LinkedHashMap<>();
        Set<String> periodSet = new LinkedHashSet<>();
        Set<String> regionSet = new LinkedHashSet<>();

        for (Map<String, Object> r : rows) {
            LocalDateTime pt = toDateTime(r.get("publishTime"));
            String period = pt == null ? "未知"
                    : String.format("%02d:00~%02d:00", pt.getHour(), (pt.getHour() + 1) % 24);
            String sent = sentimentZh((String) r.get("sentimentText"));
            String region = normalizeRegion((String) r.get("region"));
            periodSet.add(period);
            regionSet.add(region);
            long likes = r.get("likes") == null ? 0 : ((Number) r.get("likes")).longValue();
            linkMap.merge(period + "|" + sent + "|" + region, likes, Long::sum);
        }

        List<String> periodOrdered = new ArrayList<>(periodSet);
        Collections.sort(periodOrdered);
        List<String> sentOrdered = Arrays.asList("积极", "中性", "消极");
        List<String> regionOrdered = new ArrayList<>(regionSet);
        Collections.sort(regionOrdered);

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (String s : periodOrdered) nodes.add(node(s));
        for (String s : sentOrdered) nodes.add(node(s));
        for (String s : regionOrdered) nodes.add(node(s));

        Map<String, Long> psMap = new LinkedHashMap<>();
        Map<String, Long> srMap = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : linkMap.entrySet()) {
            String[] p = e.getKey().split("\\|");
            psMap.merge(p[0] + "|" + p[1], e.getValue(), Long::sum);
            srMap.merge(p[1] + "|" + p[2], e.getValue(), Long::sum);
        }
        List<Map<String, Object>> links = new ArrayList<>();
        appendLinks(links, psMap);
        appendLinks(links, srMap);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", nodes);
        result.put("links", links);
        cache.put(cacheKey, JSON.toJSONString(result), CACHE_TTL);
        return result;
    }

    private static Map<String, Object> node(String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        return m;
    }

    private static void appendLinks(List<Map<String, Object>> links, Map<String, Long> map) {
        for (Map.Entry<String, Long> e : map.entrySet()) {
            String[] p = e.getKey().split("\\|");
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("source", p[0]);
            l.put("target", p[1]);
            l.put("value", e.getValue());
            links.add(l);
        }
    }

    private static LocalDateTime toDateTime(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof LocalDateTime) {
            return (LocalDateTime) o;
        }
        if (o instanceof java.sql.Timestamp) {
            return ((java.sql.Timestamp) o).toLocalDateTime();
        }
        try {
            return LocalDateTime.parse(o.toString().replace(' ', 'T'));
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------ 话题报告

    @Override
    public String getTopic(String videoId) {
        Long vid = requireVideoId(videoId);
        if (vid == null) {
            return "";
        }
        governance.requireReadComments(vid);

        String cacheKey = CACHE_PREFIX + "topic:" + vid;
        String cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        List<Map<String, Object>> rows = commentMapper.commentRowsWithSentiment(vid);
        if (rows.isEmpty()) {
            return "";
        }
        Map<String, Object> dims = computeTopicDims(rows, videoId);
        String report = callTopicAgent(dims);
        if (report == null) {
            log.info("[topic] Agent 不可用，降级为统计报告 video={}", videoId);
            report = buildFallbackReport(dims);
        }
        cache.put(cacheKey, report, CACHE_TTL);
        return report;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> computeTopicDims(List<Map<String, Object>> rows, String videoId) {
        Map<String, Object> dims = new LinkedHashMap<>();
        dims.put("totalComments", rows.size());
        dims.put("totalUsers", rows.stream().map(r -> String.valueOf(r.get("nickname"))).distinct().count());
        dims.put("totalLikes", rows.stream().mapToLong(r -> r.get("likes") == null ? 0 : ((Number) r.get("likes")).longValue()).sum());
        dims.put("videoId", videoId);

        List<Map<String, Object>> words = getWordCloud(videoId);
        dims.put("keywords", words.stream().limit(15).collect(Collectors.toList()));

        long q = 0, ex = 0, im = 0;
        for (Map<String, Object> r : rows) {
            String t = String.valueOf(r.get("content"));
            if (t.contains("?") || t.contains("？") || t.contains("吗") || t.contains("呢")
                    || t.contains("怎么") || t.contains("如何")) q++;
            if (t.contains("!") || t.contains("！") || (t.contains("太") && t.length() < 30)) ex++;
            if (t.contains("建议") || t.contains("应该") || t.contains("推荐")) im++;
        }
        long st = Math.max(0, rows.size() - q - ex - im);
        Map<String, Object> ss = new LinkedHashMap<>();
        ss.put("question", q);
        ss.put("exclamation", ex);
        ss.put("statement", st);
        ss.put("imperative", im);
        dims.put("sentenceStructure", ss);

        List<Map<String, Object>> sentiments = getSentimentStats(videoId);
        dims.put("sentimentDistribution", sentiments);
        String mainSent = "中性";
        long mainPct = 0;
        for (Map<String, Object> s : sentiments) {
            long p = ((BigDecimal) s.get("percentage")).longValue();
            if (p > mainPct) {
                mainPct = p;
                mainSent = (String) s.get("sentiment");
            }
        }
        dims.put("dominantSentiment", mainSent);

        List<Map<String, Object>> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparing(r -> {
            LocalDateTime d = toDateTime(r.get("publishTime"));
            return d == null ? LocalDateTime.MIN : d;
        }));
        int mid = sorted.size() / 2;
        long earlyPos = sorted.subList(0, mid).stream()
                .filter(r -> "positive".equals(r.get("sentimentText"))).count();
        long latePos = sorted.subList(mid, sorted.size()).stream()
                .filter(r -> "positive".equals(r.get("sentimentText"))).count();
        String trend = "";
        if (latePos > earlyPos + mid * 0.1) trend = "随着讨论深入，正面评价逐渐增多";
        else if (earlyPos > latePos + mid * 0.1) trend = "后期讨论中出现更多不同声音";
        dims.put("sentimentTrendDesc", trend);

        // 地区 Top
        dims.put("regionDistribution", getRegionStats(videoId).stream().limit(8).collect(Collectors.toList()));
        // 高赞代表评论
        dims.put("topComments", rows.stream()
                .sorted((a, b) -> Long.compare(num(b.get("likes")), num(a.get("likes"))))
                .limit(10)
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("nickname", r.get("nickname"));
                    m.put("content", r.get("content"));
                    m.put("likes", r.get("likes"));
                    return m;
                }).collect(Collectors.toList()));
        return dims;
    }

    private static long num(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> toMapList(List<?> raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (Object o : raw) {
            if (o instanceof Map) {
                out.add((Map<String, Object>) o);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private String callTopicAgent(Map<String, Object> dims) {
        try {
            String body = httpPost(appProperties.getFlaskBaseUrl() + "/analyze_topic", JSON.toJSONString(dims));
            if (body == null) {
                return null;
            }
            JSONObject resp = JSON.parseObject(body);
            if (resp != null && resp.getIntValue("code") == 0) {
                return resp.getString("data");
            }
        } catch (Exception e) {
            log.warn("[topic] Agent 调用失败: {}", e.getMessage());
        }
        return null;
    }

    private String buildFallbackReport(Map<String, Object> dims) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 核心话题分析（统计降级版）\n\n");
        sb.append("> AI 服务当前不可用，以下为基于统计规则的本地报告。\n\n");
        sb.append("## 数据概览\n\n");
        sb.append("- 评论总数：").append(dims.get("totalComments")).append("\n");
        sb.append("- 参与用户：").append(dims.get("totalUsers")).append("\n");
        sb.append("- 总点赞：").append(dims.get("totalLikes")).append("\n\n");

        List<Map<String, Object>> kw = (List<Map<String, Object>>) dims.get("keywords");
        if (kw != null && !kw.isEmpty()) {
            sb.append("## 高频关键词\n\n");
            sb.append(kw.stream().map(k -> k.get("name") + "(" + k.get("value") + ")")
                    .collect(Collectors.joining("、"))).append("\n\n");
        }
        List<Map<String, Object>> sent = (List<Map<String, Object>>) dims.get("sentimentDistribution");
        if (sent != null) {
            sb.append("## 情感分布\n\n");
            for (Map<String, Object> s : sent) {
                sb.append("- ").append(s.get("sentiment")).append("：")
                        .append(s.get("count")).append(" 条（").append(s.get("percentage")).append("%）\n");
            }
            sb.append("\n");
        }
        sb.append("## 判断\n\n");
        sb.append("整体以「").append(dims.get("dominantSentiment")).append("」情绪为主。");
        String trend = String.valueOf(dims.getOrDefault("sentimentTrendDesc", ""));
        if (!trend.isEmpty()) {
            sb.append(trend).append("。");
        }
        sb.append("\n");
        return sb.toString();
    }

    // ------------------------------------------------------------ 刷新

    @Override
    public Map<String, Object> refreshAnalysis(String videoId) {
        Long vid = requireVideoId(videoId);
        if (vid == null) {
            throw new IllegalArgumentException("视频不存在");
        }
        governance.requireReadComments(vid);
        cache.deleteByPrefix(CACHE_PREFIX + "*");

        Map<String, Object> row = commentMapper.summaryRow(vid);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("videoId", videoId);
        out.put("totalComments", row == null || row.get("total") == null ? 0 : ((Number) row.get("total")).longValue());
        out.put("analyzedAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        return out;
    }

    // ------------------------------------------------------------ HTTP

    private String httpGet(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(20000);
        applyInternalToken(conn);
        return readBody(conn);
    }

    private String httpPost(String url, String json) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(60000);
        conn.setDoOutput(true);
        applyInternalToken(conn);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes("UTF-8"));
        }
        return readBody(conn);
    }

    /** 服务间认证头（U6）：Flask 配置了令牌时会校验 */
    private void applyInternalToken(HttpURLConnection conn) {
        String token = System.getenv("FLASK_INTERNAL_TOKEN");
        if (token != null && !token.isEmpty()) {
            conn.setRequestProperty("X-Internal-Token", token);
        }
    }

    private String readBody(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        if (code != 200) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }
}
