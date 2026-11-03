package com.coolskyai.coolpilot.insight.controller;

import com.coolskyai.coolpilot.insight.annotation.RequireRole;
import com.coolskyai.coolpilot.insight.dto.Result;
import com.coolskyai.coolpilot.insight.mapper.UsageRecordMapper;
import com.coolskyai.coolpilot.insight.service.AiAnalysisService;
import com.coolskyai.coolpilot.insight.service.GovernanceService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiAnalysisService aiService;
    private final UsageRecordMapper usageMapper;
    private final GovernanceService governance;

    public AiController(AiAnalysisService aiService, UsageRecordMapper usageMapper,
                        GovernanceService governance) {
        this.aiService = aiService;
        this.usageMapper = usageMapper;
        this.governance = governance;
    }

    /** 提交评论级结构化分析（异步，返回 taskId 供轮询） */
    @RequireRole
    @PostMapping("/analyze-comments")
    public Result<Map<String, Object>> analyzeComments(@RequestParam String videoId,
                                                       @RequestParam(defaultValue = "100") int limit) {
        return Result.ok(aiService.submitCommentAnalysis(videoId, limit));
    }

    /** 生成视频级洞察（同步，可能耗时数十秒） */
    @RequireRole
    @PostMapping("/video-insight")
    public Result<Map<String, Object>> videoInsight(@RequestParam String videoId) {
        Map<String, Object> out = aiService.generateVideoInsight(videoId);
        governance.audit("GENERATE_VIDEO_INSIGHT", "VIDEO", null, videoId);
        return Result.ok(out);
    }

    @RequireRole
    @GetMapping("/video-insights")
    public Result<List<Map<String, Object>>> insights(@RequestParam String videoId) {
        return Result.ok(aiService.listInsights(videoId));
    }

    @RequireRole
    @GetMapping("/comment-summary")
    public Result<Map<String, Object>> commentSummary(@RequestParam String videoId) {
        return Result.ok(aiService.commentAnalysisSummary(videoId));
    }

    @RequireRole
    @GetMapping("/tasks")
    public Result<List<Map<String, Object>>> tasks(@RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return Result.ok(aiService.listTasks(page, size));
    }

    @RequireRole
    @GetMapping("/tasks/{id}")
    public Result<Map<String, Object>> task(@PathVariable Long id) {
        return Result.ok(aiService.getTask(id));
    }

    /** 成本与用量看板 */
    @RequireRole
    @GetMapping("/usage")
    public Result<Map<String, Object>> usage() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("todayTokens", usageMapper.todayTokens());
        out.put("todayCost", usageMapper.todayCost());
        out.put("dailyTokenBudget", governance.getInt("ai.daily_token_budget", 2_000_000));
        out.put("singleTaskTokenBudget", governance.getInt("ai.single_task_token_budget", 200_000));
        out.put("priceVersion", governance.get("ai.price_version"));
        return Result.ok(out);
    }
}
