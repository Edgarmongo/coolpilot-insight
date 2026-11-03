package com.coolskyai.coolpilot.insight.controller;

import com.coolskyai.coolpilot.insight.annotation.RequireRole;
import com.coolskyai.coolpilot.insight.dto.Result;
import com.coolskyai.coolpilot.insight.service.OverviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 全局运营概览（跨视频）。
 */
@RestController
@RequestMapping("/api/overview")
public class OverviewController {

    private final OverviewService overviewService;

    public OverviewController(OverviewService overviewService) {
        this.overviewService = overviewService;
    }

    @RequireRole
    @GetMapping
    public Result<Map<String, Object>> overview() {
        return Result.ok(overviewService.overview());
    }

    @RequireRole
    @GetMapping("/video-options")
    public Result<List<Map<String, Object>>> videoOptions() {
        return Result.ok(overviewService.videoOptions());
    }
}
