package com.coolpilot.insight.source.controller;

import com.coolpilot.insight.common.security.RequireRole;
import com.coolpilot.insight.common.web.Result;
import com.coolpilot.insight.source.application.KeywordService;
import com.coolpilot.insight.source.application.SourceQueryService;
import com.coolpilot.insight.source.dto.DataSourceView;
import com.coolpilot.insight.source.dto.KeywordView;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 数据源与关键词接口（R1-06 规范路径）。
 *
 * <p>注意：旧的 {@code /api/keyword} 控制器仍然保留，供现有前端使用；
 * R7 前端切到本控制器后再删除旧实现。
 */
@RestController
@RequestMapping("/api/v1/source")
public class SourceController {

    private final SourceQueryService sourceQueryService;
    private final KeywordService keywordService;

    public SourceController(SourceQueryService sourceQueryService, KeywordService keywordService) {
        this.sourceQueryService = sourceQueryService;
        this.keywordService = keywordService;
    }

    @RequireRole(role = 0)
    @GetMapping("/sources")
    public Result<List<DataSourceView>> listSources() {
        return Result.ok(sourceQueryService.list());
    }

    @RequireRole(role = 1)
    @PutMapping("/sources/{code}/status")
    public Result<String> updateSourceStatus(@PathVariable String code,
                                             @RequestBody Map<String, String> body) {
        sourceQueryService.updateStatus(code, body.get("status"));
        return Result.ok("已更新");
    }

    @RequireRole(role = 0)
    @GetMapping("/keywords")
    public Result<List<KeywordView>> listKeywords(@RequestParam(required = false) Long sourceId) {
        return Result.ok(keywordService.list(sourceId));
    }

    @RequireRole(role = 0)
    @PostMapping("/keywords")
    public Result<KeywordView> createKeyword(@RequestBody Map<String, String> body) {
        return Result.ok(keywordService.create(body.get("sourceCode"), body.get("keyword")));
    }

    @RequireRole(role = 0)
    @PostMapping("/keywords/{id}/disable")
    public Result<String> disableKeyword(@PathVariable Long id) {
        keywordService.disable(id);
        return Result.ok("已禁用");
    }

    @RequireRole(role = 0)
    @DeleteMapping("/keywords/{id}")
    public Result<String> deleteKeyword(@PathVariable Long id) {
        keywordService.delete(id);
        return Result.ok("已删除");
    }
}
