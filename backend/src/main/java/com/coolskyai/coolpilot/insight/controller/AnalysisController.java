package com.coolskyai.coolpilot.insight.controller;

import com.coolskyai.coolpilot.insight.annotation.RequireRole;
import com.coolskyai.coolpilot.insight.dto.Result;
import com.coolskyai.coolpilot.insight.service.AnalysisService;
import com.coolskyai.coolpilot.insight.service.CompareService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private CompareService compareService;

    /** 多视频联合分析：并排对比需求/痛点/情感 */
    @RequireRole
    @GetMapping("/compare")
    public Result<Map<String, Object>> compare(@RequestParam String videoIds) {
        List<String> ids = Arrays.stream(videoIds.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
        return Result.ok(compareService.compare(ids));
    }

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
    public Result<Map<String, Object>> refresh(@RequestParam String videoId) {
        return Result.ok(analysisService.refreshAnalysis(videoId));
    }
}
