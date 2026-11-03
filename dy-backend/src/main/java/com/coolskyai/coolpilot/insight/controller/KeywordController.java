package com.coolskyai.coolpilot.insight.controller;

import com.coolskyai.coolpilot.insight.annotation.RequireRole;
import com.coolskyai.coolpilot.insight.dto.Result;
import com.coolskyai.coolpilot.insight.service.DiscoveryService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/keyword")
public class KeywordController {

    private final DiscoveryService discoveryService;

    public KeywordController(DiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    @RequireRole
    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody Map<String, String> body) {
        return Result.ok(discoveryService.createKeyword(body.get("keyword"), body.get("description")));
    }

    @RequireRole
    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        return Result.ok(discoveryService.listKeywords());
    }

    @RequireRole
    @DeleteMapping("/{id}")
    public Result<String> disable(@PathVariable Long id) {
        discoveryService.deleteKeyword(id);
        return Result.ok("已停用");
    }

    /** 触发一次关键词发现 */
    @RequireRole
    @PostMapping("/{id}/discover")
    public Result<Map<String, Object>> discover(@PathVariable Long id,
                                                @RequestParam(defaultValue = "50") int max,
                                                @RequestBody(required = false) Map<String, Object> params) {
        return Result.ok(discoveryService.submitDiscovery(id, max, params));
    }

    @RequireRole
    @GetMapping("/{id}/videos")
    public Result<List<Map<String, Object>>> videos(@PathVariable Long id) {
        return Result.ok(discoveryService.listKeywordVideos(id));
    }

    @RequireRole
    @GetMapping("/discovery-tasks")
    public Result<List<Map<String, Object>>> tasks() {
        return Result.ok(discoveryService.listTasks());
    }
}
