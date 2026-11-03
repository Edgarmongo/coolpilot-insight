package com.coolskyai.coolpilot.insight.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.coolskyai.coolpilot.insight.config.AppProperties;
import com.coolskyai.coolpilot.insight.entity.*;
import com.coolskyai.coolpilot.insight.mapper.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * AI 洞察编排：评论级结构化分析 + 视频级洞察。
 *
 * <p>设计要点：
 * <ul>
 *   <li>每次调用都写 {@code usage_record}（唯一成本权威源）</li>
 *   <li>同一 profile 重复分析用 {@code ON DUPLICATE KEY UPDATE} 覆盖，不重复计费</li>
 *   <li>预算护栏：日 Token 上限 + 单任务上限，超限直接拒绝</li>
 *   <li>所有结论落 {@code analysis_evidence} 可追溯到原始评论</li>
 * </ul>
 */
@Service
public class AiAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AiAnalysisService.class);
    private static final int BATCH_SIZE = 20;
    private static final int MAX_COMMENTS_PER_TASK = 300;

    private final AnalysisProfileMapper profileMapper;
    private final AnalysisTaskMapper taskMapper;
    private final AnalysisTaskBatchMapper batchMapper;
    private final AnalysisTaskItemMapper itemMapper;
    private final CommentAnalysisMapper commentAnalysisMapper;
    private final VideoInsightMapper insightMapper;
    private final AnalysisEvidenceMapper evidenceMapper;
    private final CommentMapper commentMapper;
    private final UsageRecordMapper usageMapper;
    private final VideoService videoService;
    private final GovernanceService governance;
    private final AppProperties appProperties;

    /** AI 调用并发上限（进程内；跨实例限流需网关层，见文档 6.6） */
    private final ExecutorService executor = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "ai-analysis");
        t.setDaemon(true);
        return t;
    });

    public AiAnalysisService(AnalysisProfileMapper profileMapper, AnalysisTaskMapper taskMapper,
                             AnalysisTaskBatchMapper batchMapper, AnalysisTaskItemMapper itemMapper,
                             CommentAnalysisMapper commentAnalysisMapper, VideoInsightMapper insightMapper,
                             AnalysisEvidenceMapper evidenceMapper, CommentMapper commentMapper,
                             UsageRecordMapper usageMapper, VideoService videoService,
                             GovernanceService governance, AppProperties appProperties) {
        this.profileMapper = profileMapper;
        this.taskMapper = taskMapper;
        this.batchMapper = batchMapper;
        this.itemMapper = itemMapper;
        this.commentAnalysisMapper = commentAnalysisMapper;
        this.insightMapper = insightMapper;
        this.evidenceMapper = evidenceMapper;
        this.commentMapper = commentMapper;
        this.usageMapper = usageMapper;
        this.videoService = videoService;
        this.governance = governance;
        this.appProperties = appProperties;
    }

    // ============================================================ 评论级

    /** 提交评论级分析任务，异步执行 */
    public Map<String, Object> submitCommentAnalysis(String videoId, int limit) {
        Long vid = videoService.resolveInternalId(videoId);
        if (vid == null) {
            throw new IllegalArgumentException("视频不存在");
        }
        governance.requireReadComments(vid);

        int max = Math.min(Math.max(limit, 1), MAX_COMMENTS_PER_TASK);
        AnalysisProfile profile = defaultProfile();

        // 跳过已分析过的评论（同 profile）
        List<Map<String, Object>> candidates = commentMapper.commentsForAnalysis(vid, max);
        Set<Long> analyzed = commentAnalysisMapper.selectList(new LambdaQueryWrapper<CommentAnalysis>()
                        .eq(CommentAnalysis::getProfileId, profile.getId()))
                .stream().map(CommentAnalysis::getCommentId).collect(Collectors.toSet());
        List<Map<String, Object>> todo = candidates.stream()
                .filter(c -> !analyzed.contains(((Number) c.get("id")).longValue()))
                .collect(Collectors.toList());

        if (todo.isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("taskId", null);
            out.put("message", "该视频的评论在当前分析版本下已全部分析完成");
            out.put("reused", candidates.size());
            return out;
        }

        long todayTokens = usageMapper.todayTokens();
        int dailyBudget = governance.getInt("ai.daily_token_budget", 2_000_000);
        if (todayTokens >= dailyBudget) {
            throw new IllegalStateException("今日 AI Token 预算已用尽（" + todayTokens + "/" + dailyBudget + "），请明日再试");
        }

        AnalysisTask task = new AnalysisTask();
        task.setTaskNo(nextNo("AT"));
        task.setTaskType("COMMENT_LEVEL");
        task.setStatus("PENDING");
        task.setOwnerUserId(governance.currentUserId());
        task.setScopeType("VIDEO");
        task.setScopeJson(JSON.toJSONString(Collections.singletonMap("videoId", videoId)));
        task.setProfileId(profile.getId());
        task.setTotalItems(todo.size());
        task.setSuccessItems(0);
        task.setFailedItems(0);
        task.setInputTokens(0L);
        task.setOutputTokens(0L);
        task.setCostAmount(BigDecimal.ZERO);
        task.setPriceVersion(governance.get("ai.price_version"));
        taskMapper.insert(task);

        for (Map<String, Object> c : todo) {
            AnalysisTaskItem item = new AnalysisTaskItem();
            item.setTaskId(task.getId());
            item.setTargetType("COMMENT");
            item.setTargetId(((Number) c.get("id")).longValue());
            item.setStatus("PENDING");
            item.setRetryCount(0);
            item.setAllocatedInputTokens(0);
            item.setAllocatedOutputTokens(0);
            item.setAllocatedCostAmount(BigDecimal.ZERO);
            itemMapper.insert(item);
        }

        Long taskId = task.getId();
        Long ownerId = governance.currentUserId();
        executor.submit(() -> runCommentTask(taskId, vid, ownerId));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", taskId);
        out.put("taskNo", task.getTaskNo());
        out.put("totalItems", todo.size());
        out.put("skipped", candidates.size() - todo.size());
        out.put("message", "评论级分析已提交");
        return out;
    }

    private void runCommentTask(Long taskId, Long videoId, Long ownerId) {
        AnalysisTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        markTask(taskId, "RUNNING", null);

        List<AnalysisTaskItem> items = itemMapper.selectList(new LambdaQueryWrapper<AnalysisTaskItem>()
                .eq(AnalysisTaskItem::getTaskId, taskId)
                .eq(AnalysisTaskItem::getStatus, "PENDING")
                .orderByAsc(AnalysisTaskItem::getId));

        int success = 0;
        int failed = 0;
        int batchNo = 0;

        for (int i = 0; i < items.size(); i += BATCH_SIZE) {
            batchNo++;
            List<AnalysisTaskItem> slice = items.subList(i, Math.min(i + BATCH_SIZE, items.size()));
            AnalysisTaskBatch batch = new AnalysisTaskBatch();
            batch.setTaskId(taskId);
            batch.setBatchNo(batchNo);
            batch.setStatus("RUNNING");
            batch.setInputCount(slice.size());
            batch.setSuccessCount(0);
            batch.setFailedCount(0);
            batch.setInputTokens(0L);
            batch.setOutputTokens(0L);
            batch.setStartedAt(LocalDateTime.now());
            batchMapper.insert(batch);

            List<Long> commentIds = slice.stream().map(AnalysisTaskItem::getTargetId).collect(Collectors.toList());
            List<Map<String, Object>> comments = commentMapper.selectBatchIds(commentIds).stream()
                    .map(c -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", c.getId());
                        m.put("content", c.getContent());
                        return m;
                    }).collect(Collectors.toList());

            int ok = 0;
            int bad = 0;
            try {
                JSONObject resp = callFlask("/analyze/comments", buildCommentPayload(task, videoId, comments));
                if (resp == null || resp.getIntValue("code") != 0) {
                    throw new IllegalStateException("AI 服务返回异常");
                }
                JSONArray results = resp.getJSONObject("data").getJSONArray("results");
                JSONObject usage = resp.getJSONObject("usage");
                long inTok = usage == null ? 0 : usage.getLongValue("input_tokens");
                long outTok = usage == null ? 0 : usage.getLongValue("output_tokens");
                BigDecimal cost = calcCost(inTok, outTok);

                for (int k = 0; k < results.size(); k++) {
                    JSONObject r = results.getJSONObject(k);
                    Long commentId = r.getLong("comment_id");
                    upsertCommentAnalysis(task, videoId, commentId, r, inTok / Math.max(results.size(), 1),
                            outTok / Math.max(results.size(), 1), cost);
                    ok++;
                }
                bad = slice.size() - ok;

                for (int k = 0; k < slice.size(); k++) {
                    AnalysisTaskItem it = slice.get(k);
                    it.setBatchId(batch.getId());
                    it.setStatus(k < ok ? "SUCCESS" : "SKIPPED");
                    it.setFinishedAt(LocalDateTime.now());
                    it.setAllocatedInputTokens((int) (inTok / Math.max(slice.size(), 1)));
                    it.setAllocatedOutputTokens((int) (outTok / Math.max(slice.size(), 1)));
                    it.setAllocatedCostAmount(cost.divide(BigDecimal.valueOf(Math.max(slice.size(), 1)),
                            6, RoundingMode.HALF_UP));
                    itemMapper.updateById(it);
                }

                batch.setStatus(bad == 0 ? "SUCCESS" : "PARTIAL_SUCCESS");
                batch.setSuccessCount(ok);
                batch.setFailedCount(bad);
                batch.setInputTokens(inTok);
                batch.setOutputTokens(outTok);
                batch.setFinishedAt(LocalDateTime.now());
                batchMapper.updateById(batch);

                recordUsage("AI_ANALYSIS", taskId, batch.getId(), ownerId,
                        task.getPriceVersion(), inTok, outTok, cost, 0, "OK");
            } catch (Exception e) {
                log.error("[ai] 批次 {} 失败: {}", batchNo, e.getMessage());
                bad = slice.size();
                for (AnalysisTaskItem it : slice) {
                    it.setBatchId(batch.getId());
                    it.setStatus("FAILED");
                    it.setErrorCode("AI_CALL_FAILED");
                    it.setErrorMessage(truncate(e.getMessage(), 500));
                    it.setFinishedAt(LocalDateTime.now());
                    itemMapper.updateById(it);
                }
                batch.setStatus("FAILED");
                batch.setFailedCount(bad);
                batch.setErrorMessage(truncate(e.getMessage(), 2000));
                batch.setFinishedAt(LocalDateTime.now());
                batchMapper.updateById(batch);
                recordUsage("AI_ANALYSIS", taskId, batch.getId(), ownerId,
                        task.getPriceVersion(), 0, 0, BigDecimal.ZERO, 0, "ERROR");
            }
            success += ok;
            failed += bad;
        }

        AnalysisTask up = new AnalysisTask();
        up.setId(taskId);
        up.setStatus(failed == 0 ? "SUCCESS" : (success == 0 ? "FAILED" : "PARTIAL_SUCCESS"));
        up.setSuccessItems(success);
        up.setFailedItems(failed);
        up.setFinishedAt(LocalDateTime.now());
        long tokens = usageMapper.tokensByTask(taskId);
        up.setInputTokens(tokens / 2);
        up.setOutputTokens(tokens / 2);
        taskMapper.updateById(up);
    }

    @SuppressWarnings("unchecked")
    private void upsertCommentAnalysis(AnalysisTask task, Long videoId, Long commentId,
                                       JSONObject r, long inTok, long outTok, BigDecimal cost) {
        CommentAnalysis exist = commentAnalysisMapper.selectOne(new LambdaQueryWrapper<CommentAnalysis>()
                .eq(CommentAnalysis::getCommentId, commentId)
                .eq(CommentAnalysis::getProfileId, task.getProfileId()).last("LIMIT 1"));
        CommentAnalysis ca = exist == null ? new CommentAnalysis() : exist;
        ca.setCommentId(commentId);
        ca.setProfileId(task.getProfileId());
        ca.setAnalysisTaskId(task.getId());
        ca.setSentiment(blankToNull(r.getString("sentiment")));
        ca.setIntent(blankToNull(r.getString("intent")));
        ca.setTopicsJson(toJsonArrayString(r.get("topics")));
        ca.setPainPointsJson(toJsonArrayString(r.get("pain_points")));
        ca.setUserNeed(truncate(r.getString("user_need"), 500));
        ca.setPurchaseIntent(blankToNull(r.getString("purchase_intent")));
        ca.setScenarioTagsJson(toJsonArrayString(r.get("scenario_tags")));
        ca.setProductSuggestionsJson(toJsonArrayString(r.get("product_suggestions")));
        ca.setCompetitorMentionsJson(toJsonArrayString(r.get("competitor_mentions")));
        BigDecimal conf = r.getBigDecimal("confidence");
        if (conf != null) {
            conf = conf.max(BigDecimal.ZERO).min(BigDecimal.ONE).setScale(3, RoundingMode.HALF_UP);
        }
        ca.setConfidence(conf);
        ca.setIsUncertain(conf == null || conf.doubleValue() < 0.5 ? 1 : 0);

        if (exist == null) {
            commentAnalysisMapper.insert(ca);
        } else {
            commentAnalysisMapper.updateById(ca);
        }

        // 评论级证据：让每条结构化结论都能回到原文
        if (ca.getId() != null) {
            AnalysisEvidence ev = new AnalysisEvidence();
            ev.setCommentAnalysisId(ca.getId());
            ev.setCommentId(commentId);
            ev.setEvidenceType("REPRESENTATIVE");
            ev.setRelevanceScore(conf);
            ev.setExplanation("评论级结构化分析结果");
            ev.setSourceLevel("COMMENT_ANALYSIS");
            evidenceMapper.insert(ev);
        }
    }

    // ============================================================ 视频级

    /** 生成视频级洞察并落库（多版本并存，不覆盖历史） */
    public Map<String, Object> generateVideoInsight(String videoId) {
        Long vid = videoService.resolveInternalId(videoId);
        if (vid == null) {
            throw new IllegalArgumentException("视频不存在");
        }
        governance.requireReadComments(vid);
        AnalysisProfile profile = defaultProfile();

        List<Map<String, Object>> rows = commentMapper.commentRowsWithSentiment(vid);
        if (rows.isEmpty()) {
            throw new IllegalStateException("该视频还没有评论数据，无法生成洞察");
        }

        long todayTokens = usageMapper.todayTokens();
        int dailyBudget = governance.getInt("ai.daily_token_budget", 2_000_000);
        if (todayTokens >= dailyBudget) {
            throw new IllegalStateException("今日 AI Token 预算已用尽，请明日再试");
        }

        AnalysisTask task = new AnalysisTask();
        task.setTaskNo(nextNo("AT"));
        task.setTaskType("VIDEO_LEVEL");
        task.setStatus("RUNNING");
        task.setOwnerUserId(governance.currentUserId());
        task.setScopeType("VIDEO");
        task.setScopeJson(JSON.toJSONString(Collections.singletonMap("videoId", videoId)));
        task.setProfileId(profile.getId());
        task.setTotalItems(1);
        task.setStartedAt(LocalDateTime.now());
        task.setPriceVersion(governance.get("ai.price_version"));
        taskMapper.insert(task);

        AnalysisTaskItem item = new AnalysisTaskItem();
        item.setTaskId(task.getId());
        item.setTargetType("VIDEO");
        item.setTargetId(vid);
        item.setStatus("RUNNING");
        itemMapper.insert(item);

        try {
            List<Map<String, Object>> sample = rows.stream()
                    .sorted((a, b) -> Long.compare(num(b.get("likes")), num(a.get("likes"))))
                    .limit(80).collect(Collectors.toList());

            JSONObject payload = new JSONObject();
            payload.put("video_id", vid);
            payload.put("stats", buildStats(vid, rows));
            payload.put("comments", sample.stream().map(r -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", r.get("id"));
                m.put("content", r.get("content"));
                m.put("likes", r.get("likes"));
                return m;
            }).collect(Collectors.toList()));
            payload.put("profile", profilePayload(profile));

            long start = System.currentTimeMillis();
            JSONObject resp = callFlask("/analyze/insight", payload);
            long latency = System.currentTimeMillis() - start;
            if (resp == null || resp.getIntValue("code") != 0) {
                throw new IllegalStateException("AI 服务返回异常，视频洞察未生成");
            }
            JSONObject data = resp.getJSONObject("data");
            JSONObject usage = resp.getJSONObject("usage");
            long inTok = usage == null ? 0 : usage.getLongValue("input_tokens");
            long outTok = usage == null ? 0 : usage.getLongValue("output_tokens");
            BigDecimal cost = calcCost(inTok, outTok);

            VideoInsight insight = new VideoInsight();
            insight.setVideoId(vid);
            insight.setAnalysisTaskId(task.getId());
            insight.setProfileId(profile.getId());
            insight.setInsightType("OVERVIEW");
            insight.setScopeJson(JSON.toJSONString(Collections.singletonMap("sampleCount", sample.size())));
            insight.setSampleCount(sample.size());
            insight.setResultJson(data.toJSONString());
            insight.setSummaryMd(truncate(data.getString("summary"), 60000));
            insight.setEvidenceCount(0);
            insightMapper.insert(insight);

            int evidenceCount = persistInsightEvidence(insight.getId(), data);

            VideoInsight patch = new VideoInsight();
            patch.setId(insight.getId());
            patch.setEvidenceCount(evidenceCount);
            insightMapper.updateById(patch);

            recordUsage("AI_ANALYSIS", task.getId(), null, task.getOwnerUserId(),
                    task.getPriceVersion(), inTok, outTok, cost, (int) latency, "OK");

            AnalysisTaskItem done = new AnalysisTaskItem();
            done.setId(item.getId());
            done.setStatus("SUCCESS");
            done.setAllocatedInputTokens((int) inTok);
            done.setAllocatedOutputTokens((int) outTok);
            done.setAllocatedCostAmount(cost);
            done.setFinishedAt(LocalDateTime.now());
            itemMapper.updateById(done);

            AnalysisTask tup = new AnalysisTask();
            tup.setId(task.getId());
            tup.setStatus("SUCCESS");
            tup.setSuccessItems(1);
            tup.setInputTokens(inTok);
            tup.setOutputTokens(outTok);
            tup.setCostAmount(cost);
            tup.setFinishedAt(LocalDateTime.now());
            taskMapper.updateById(tup);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("insightId", insight.getId());
            out.put("taskId", task.getId());
            out.put("sampleCount", sample.size());
            out.put("evidenceCount", evidenceCount);
            out.put("inputTokens", inTok);
            out.put("outputTokens", outTok);
            out.put("costAmount", cost);
            out.put("result", data);
            return out;
        } catch (Exception e) {
            AnalysisTaskItem done = new AnalysisTaskItem();
            done.setId(item.getId());
            done.setStatus("FAILED");
            done.setErrorCode("AI_CALL_FAILED");
            done.setErrorMessage(truncate(e.getMessage(), 500));
            done.setFinishedAt(LocalDateTime.now());
            itemMapper.updateById(done);

            AnalysisTask tup = new AnalysisTask();
            tup.setId(task.getId());
            tup.setStatus("FAILED");
            tup.setFailedItems(1);
            tup.setErrorMessage(truncate(e.getMessage(), 2000));
            tup.setFinishedAt(LocalDateTime.now());
            taskMapper.updateById(tup);
            throw new IllegalStateException("洞察生成失败：" + e.getMessage());
        }
    }

    /** 把结论里的 evidence 评论 ID 落成证据行 */
    private int persistInsightEvidence(Long insightId, JSONObject data) {
        int count = 0;
        for (String key : new String[]{"needs", "pain_points", "product_opportunities", "highlights"}) {
            JSONArray arr = data.getJSONArray(key);
            if (arr == null) {
                continue;
            }
            for (int i = 0; i < arr.size(); i++) {
                JSONObject o = arr.getJSONObject(i);
                JSONArray ids = o.getJSONArray("evidence_comment_ids");
                if (ids == null) {
                    continue;
                }
                for (int j = 0; j < ids.size(); j++) {
                    Long cid = ids.getLong(j);
                    if (cid == null) {
                        continue;
                    }
                    AnalysisEvidence ev = new AnalysisEvidence();
                    ev.setInsightId(insightId);
                    ev.setCommentId(cid);
                    ev.setEvidenceType("SUPPORTING");
                    ev.setExplanation(truncate(o.getString("title"), 500));
                    ev.setSourceLevel("INSIGHT");
                    try {
                        evidenceMapper.insert(ev);
                        count++;
                    } catch (Exception ignore) {
                        // 评论可能已被删除，跳过该条证据
                    }
                }
            }
        }
        return count;
    }

    private Map<String, Object> buildStats(Long vid, List<Map<String, Object>> rows) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalComments", rows.size());
        stats.put("totalLikes", rows.stream().mapToLong(r -> num(r.get("likes"))).sum());
        Map<String, Long> sent = new LinkedHashMap<>();
        Map<String, Long> region = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String s = r.get("sentimentText") == null ? "unknown" : String.valueOf(r.get("sentimentText"));
            sent.merge(s, 1L, Long::sum);
            String reg = r.get("region") == null ? "未知" : String.valueOf(r.get("region"));
            region.merge(reg, 1L, Long::sum);
        }
        stats.put("sentiment", sent);
        stats.put("regionTop", region.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(8)
                .map(e -> Collections.singletonMap(e.getKey(), e.getValue()))
                .collect(Collectors.toList()));
        return stats;
    }

    // ============================================================ 查询

    public List<Map<String, Object>> listInsights(String videoId) {
        Long vid = videoService.resolveInternalId(videoId);
        if (vid == null) {
            return Collections.emptyList();
        }
        governance.requireReadComments(vid);
        return insightMapper.selectList(new LambdaQueryWrapper<VideoInsight>()
                        .eq(VideoInsight::getVideoId, vid)
                        .orderByDesc(VideoInsight::getId).last("LIMIT 20"))
                .stream().map(v -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", v.getId());
                    m.put("insightType", v.getInsightType());
                    m.put("sampleCount", v.getSampleCount());
                    m.put("evidenceCount", v.getEvidenceCount());
                    m.put("createdAt", v.getCreatedAt());
                    m.put("summary", v.getSummaryMd());
                    m.put("result", JSON.parseObject(v.getResultJson()));
                    return m;
                }).collect(Collectors.toList());
    }

    public Map<String, Object> getTask(Long taskId) {
        AnalysisTask t = taskMapper.selectById(taskId);
        if (t == null) {
            throw new IllegalArgumentException("分析任务不存在");
        }
        if (!governance.isAdmin() && !Objects.equals(t.getOwnerUserId(), governance.currentUserId())) {
            throw new SecurityException("无权查看该分析任务");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", t.getId());
        out.put("taskNo", t.getTaskNo());
        out.put("taskType", t.getTaskType());
        out.put("status", t.getStatus());
        out.put("totalItems", t.getTotalItems());
        out.put("successItems", t.getSuccessItems());
        out.put("failedItems", t.getFailedItems());
        out.put("inputTokens", t.getInputTokens());
        out.put("outputTokens", t.getOutputTokens());
        out.put("costAmount", t.getCostAmount());
        out.put("createdAt", t.getCreatedAt());
        out.put("finishedAt", t.getFinishedAt());
        out.put("errorMessage", t.getErrorMessage());
        List<Map<String, Object>> batches = batchMapper.selectList(new LambdaQueryWrapper<AnalysisTaskBatch>()
                        .eq(AnalysisTaskBatch::getTaskId, taskId).orderByAsc(AnalysisTaskBatch::getBatchNo))
                .stream().map(b -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("batchNo", b.getBatchNo());
                    m.put("status", b.getStatus());
                    m.put("inputCount", b.getInputCount());
                    m.put("successCount", b.getSuccessCount());
                    m.put("failedCount", b.getFailedCount());
                    m.put("inputTokens", b.getInputTokens());
                    m.put("outputTokens", b.getOutputTokens());
                    return m;
                }).collect(Collectors.toList());
        out.put("batches", batches);
        return out;
    }

    public List<Map<String, Object>> listTasks(int page, int size) {
        LambdaQueryWrapper<AnalysisTask> wrapper = new LambdaQueryWrapper<AnalysisTask>()
                .orderByDesc(AnalysisTask::getId);
        if (!governance.isAdmin()) {
            wrapper.eq(AnalysisTask::getOwnerUserId, governance.currentUserId());
        }
        wrapper.last("LIMIT " + Math.min(Math.max(size, 1), 100)
                + " OFFSET " + Math.max(page - 1, 0) * Math.max(size, 1));
        return taskMapper.selectList(wrapper).stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("taskNo", t.getTaskNo());
            m.put("taskType", t.getTaskType());
            m.put("status", t.getStatus());
            m.put("totalItems", t.getTotalItems());
            m.put("successItems", t.getSuccessItems());
            m.put("costAmount", t.getCostAmount());
            m.put("createdAt", t.getCreatedAt());
            return m;
        }).collect(Collectors.toList());
    }

    /** 评论级分析结果（用于页面展示需求/痛点分布） */
    public Map<String, Object> commentAnalysisSummary(String videoId) {
        Long vid = videoService.resolveInternalId(videoId);
        if (vid == null) {
            return Collections.emptyMap();
        }
        governance.requireReadComments(vid);

        List<Map<String, Object>> rows = commentMapper.commentRowsWithSentiment(vid);
        List<Long> ids = rows.stream().map(r -> ((Number) r.get("id")).longValue()).collect(Collectors.toList());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        // 分批查询避免 IN 过长
        List<CommentAnalysis> analyses = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += 500) {
            List<Long> slice = ids.subList(i, Math.min(i + 500, ids.size()));
            analyses.addAll(commentAnalysisMapper.selectList(new LambdaQueryWrapper<CommentAnalysis>()
                    .in(CommentAnalysis::getCommentId, slice)
                    .orderByDesc(CommentAnalysis::getId)));
        }
        // 每条评论只保留最新一条
        Map<Long, CommentAnalysis> latest = new LinkedHashMap<>();
        for (CommentAnalysis ca : analyses) {
            latest.putIfAbsent(ca.getCommentId(), ca);
        }

        Map<String, Long> intents = new LinkedHashMap<>();
        Map<String, Long> purchase = new LinkedHashMap<>();
        Map<String, Long> topics = new LinkedHashMap<>();
        Map<String, Long> pains = new LinkedHashMap<>();
        List<Map<String, Object>> needs = new ArrayList<>();

        for (CommentAnalysis ca : latest.values()) {
            if (ca.getIntent() != null) intents.merge(ca.getIntent(), 1L, Long::sum);
            if (ca.getPurchaseIntent() != null) purchase.merge(ca.getPurchaseIntent(), 1L, Long::sum);
            countJson(ca.getTopicsJson(), topics);
            countJson(ca.getPainPointsJson(), pains);
        }
        for (Map.Entry<Long, CommentAnalysis> e : latest.entrySet()) {
            if (e.getValue().getUserNeed() != null && !e.getValue().getUserNeed().isEmpty()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("commentId", e.getKey());
                m.put("userNeed", e.getValue().getUserNeed());
                m.put("confidence", e.getValue().getConfidence());
                needs.add(m);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("analyzedComments", latest.size());
        out.put("totalComments", rows.size());
        out.put("intentDistribution", toPairs(intents));
        out.put("purchaseIntentDistribution", toPairs(purchase));
        out.put("topicTop", toPairs(topics).stream().limit(15).collect(Collectors.toList()));
        out.put("painPointTop", toPairs(pains).stream().limit(15).collect(Collectors.toList()));
        out.put("needs", needs.stream().limit(50).collect(Collectors.toList()));
        return out;
    }

    private static void countJson(String json, Map<String, Long> counter) {
        if (json == null || json.isEmpty()) {
            return;
        }
        try {
            JSONArray arr = JSON.parseArray(json);
            for (int i = 0; i < arr.size(); i++) {
                String v = arr.getString(i);
                if (v != null && !v.isEmpty()) {
                    counter.merge(v, 1L, Long::sum);
                }
            }
        } catch (Exception ignored) {
            // 非数组则忽略
        }
    }

    private static List<Map<String, Object>> toPairs(Map<String, Long> map) {
        return map.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", e.getKey());
                    m.put("value", e.getValue());
                    return m;
                }).collect(Collectors.toList());
    }

    // ============================================================ 内部

    private AnalysisProfile defaultProfile() {
        AnalysisProfile p = profileMapper.selectOne(new LambdaQueryWrapper<AnalysisProfile>()
                .eq(AnalysisProfile::getProfileKey, "ap_default_v1").last("LIMIT 1"));
        if (p == null) {
            throw new IllegalStateException("默认分析档案缺失，请检查数据库迁移是否执行");
        }
        return p;
    }

    private Map<String, Object> profilePayload(AnalysisProfile p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", p.getProvider());
        m.put("model", p.getModelName());
        m.put("prompt_version", p.getPromptVersion());
        m.put("schema_version", p.getSchemaVersion());
        m.put("taxonomy_version", p.getTaxonomyVersion());
        m.put("params", p.getParamsJson() == null ? Collections.emptyMap() : JSON.parseObject(p.getParamsJson()));
        return m;
    }

    private JSONObject buildCommentPayload(AnalysisTask task, Long videoId, List<Map<String, Object>> comments) {
        JSONObject payload = new JSONObject();
        payload.put("video_id", videoId);
        payload.put("comments", comments);
        AnalysisProfile p = profileMapper.selectById(task.getProfileId());
        payload.put("profile", profilePayload(p));
        return payload;
    }

    private BigDecimal calcCost(long inTok, long outTok) {
        BigDecimal inPrice = governance.getDecimal("ai.input_price_per_m", new BigDecimal("2.0"));
        BigDecimal outPrice = governance.getDecimal("ai.output_price_per_m", new BigDecimal("8.0"));
        BigDecimal in = BigDecimal.valueOf(inTok).multiply(inPrice)
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
        BigDecimal out = BigDecimal.valueOf(outTok).multiply(outPrice)
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
        return in.add(out).setScale(6, RoundingMode.HALF_UP);
    }

    private void recordUsage(String bizType, Long bizRefId, Long batchId, Long ownerId,
                             String priceVersion, long inTok, long outTok,
                             BigDecimal cost, int latencyMs, String status) {
        UsageRecord u = new UsageRecord();
        u.setBizType(bizType);
        u.setBizRefId(bizRefId);
        u.setBatchId(batchId);
        u.setOwnerUserId(ownerId);
        u.setProvider("deepseek");
        u.setModelName("deepseek-chat");
        u.setInputTokens((int) inTok);
        u.setOutputTokens((int) outTok);
        u.setCostAmount(cost);
        u.setPriceVersion(priceVersion);
        u.setLatencyMs(latencyMs);
        u.setStatus(status);
        usageMapper.insert(u);
    }

    private void markTask(Long taskId, String status, String error) {
        AnalysisTask up = new AnalysisTask();
        up.setId(taskId);
        up.setStatus(status);
        up.setStartedAt(LocalDateTime.now());
        up.setErrorMessage(error);
        taskMapper.updateById(up);
    }

    private JSONObject callFlask(String path, JSONObject payload) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(appProperties.getFlaskBaseUrl() + path).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            String token = System.getenv("FLASK_INTERNAL_TOKEN");
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty("X-Internal-Token", token);
            }
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(180000);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.toJSONString().getBytes("UTF-8"));
            }
            if (conn.getResponseCode() != 200) {
                log.warn("[ai] Flask {} 返回 {}", path, conn.getResponseCode());
                return null;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line);
                }
            }
            return JSON.parseObject(sb.toString());
        } catch (Exception e) {
            log.error("[ai] 调用 Flask {} 失败: {}", path, e.getMessage());
            return null;
        }
    }

    private static String toJsonArrayString(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof JSONArray) {
            return ((JSONArray) o).toJSONString();
        }
        return JSON.toJSONString(o);
    }

    private static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static long num(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    private static String nextNo(String prefix) {
        return prefix + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%03d", new Random().nextInt(1000));
    }
}
