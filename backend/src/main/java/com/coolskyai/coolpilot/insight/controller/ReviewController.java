package com.coolskyai.coolpilot.insight.controller;

import com.coolskyai.coolpilot.insight.annotation.RequireRole;
import com.coolskyai.coolpilot.insight.dto.Result;
import com.coolskyai.coolpilot.insight.service.ReviewService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * AI 结果人工复核（能力移植自 DYP 的 /analyses/{id}/review）。
 */
@RestController
@RequestMapping("/api/review")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /** 复核队列，默认低置信度优先 */
    @RequireRole
    @GetMapping("/queue")
    public Result<Map<String, Object>> queue(@RequestParam String videoId,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) Boolean lowConfidence,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return Result.ok(reviewService.queue(videoId, status, lowConfidence, page, size));
    }

    @RequireRole
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats(@RequestParam String videoId) {
        return Result.ok(reviewService.stats(videoId));
    }

    /** 提交复核：APPROVED / REJECTED / CORRECTED */
    @RequireRole
    @PostMapping("/{analysisId}")
    public Result<Map<String, Object>> submit(@PathVariable Long analysisId,
                                              @RequestBody Map<String, Object> body) {
        String status = body.get("status") == null ? null : String.valueOf(body.get("status"));
        String note = body.get("note") == null ? null : String.valueOf(body.get("note"));
        @SuppressWarnings("unchecked")
        Map<String, Object> corrections = body.get("corrections") instanceof Map
                ? (Map<String, Object>) body.get("corrections") : null;
        return Result.ok(reviewService.submit(analysisId, status, note, corrections));
    }

    @RequireRole
    @GetMapping("/{analysisId}/history")
    public Result<List<Map<String, Object>>> history(@PathVariable Long analysisId) {
        return Result.ok(reviewService.history(analysisId));
    }
}
