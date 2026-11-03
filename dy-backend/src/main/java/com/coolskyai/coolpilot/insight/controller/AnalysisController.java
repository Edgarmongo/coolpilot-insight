package com.coolskyai.coolpilot.insight.controller;

import com.coolskyai.coolpilot.insight.annotation.RequireRole;
import com.coolskyai.coolpilot.insight.dto.Result;
import com.coolskyai.coolpilot.insight.entity.AnalysisResult;
import com.coolskyai.coolpilot.insight.service.AnalysisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    @Autowired
    private AnalysisService analysisService;

    @RequireRole
    @GetMapping("/summary")
    public Result<Map<String, Object>> summary(@RequestParam String videoId) {
        return Result.ok(analysisService.getSummary(videoId));
    }

    @RequireRole
    @GetMapping("/region")
    public Result<List<Map<String, Object>>> region(@RequestParam String videoId) {
        return Result.ok(analysisService.getRegionStats(videoId));
    }

    @RequireRole
    @GetMapping("/sentiment")
    public Result<List<Map<String, Object>>> sentiment(@RequestParam String videoId) {
        return Result.ok(analysisService.getSentimentStats(videoId));
    }

    @RequireRole
    @GetMapping("/trend")
    public Result<List<Map<String, Object>>> trend(@RequestParam String videoId) {
        return Result.ok(analysisService.getTrendStats(videoId));
    }

    @RequireRole
    @GetMapping("/top-comments")
    public Result<List<Map<String, Object>>> topComments(@RequestParam String videoId) {
        return Result.ok(analysisService.getTopComments(videoId));
    }

    @RequireRole
    @GetMapping("/videos")
    public Result<List<Map<String, Object>>> videos() {
        return Result.ok(analysisService.getVideoList());
    }

    @RequireRole
    @GetMapping("/wordcloud")
    public Result<List<Map<String, Object>>> wordcloud(@RequestParam String videoId) {
        return Result.ok(analysisService.getWordCloud(videoId));
    }

    @RequireRole
    @GetMapping("/sankey")
    public Result<Map<String, Object>> sankey(@RequestParam String videoId) {
        return Result.ok(analysisService.getSankey(videoId));
    }

    @RequireRole
    @GetMapping("/topic")
    public Result<String> topic(@RequestParam String videoId) {
        return Result.ok(analysisService.getTopic(videoId));
    }

    @RequireRole
    @PostMapping("/refresh")
    public Result<AnalysisResult> refresh(@RequestParam String videoId) {
        return Result.ok(analysisService.refreshAnalysis(videoId));
    }
}
