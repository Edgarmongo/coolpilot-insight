package com.coolskyai.coolpilot.insight.controller;

import com.coolskyai.coolpilot.insight.annotation.RequireRole;
import com.coolskyai.coolpilot.insight.dto.Result;
import com.coolskyai.coolpilot.insight.service.ReportService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/report")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @RequireRole
    @PostMapping
    public Result<Map<String, Object>> create(@RequestParam String videoId,
                                              @RequestParam(required = false) String title) {
        return Result.ok(reportService.createFromVideo(videoId, title));
    }

    @RequireRole
    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        return Result.ok(reportService.list());
    }

    @RequireRole
    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        return Result.ok(reportService.detail(id));
    }

    @RequireRole
    @GetMapping("/{id}/versions")
    public Result<List<Map<String, Object>>> versions(@PathVariable Long id) {
        return Result.ok(reportService.versions(id));
    }

    @RequireRole
    @GetMapping("/{id}/export")
    public Result<Map<String, Object>> export(@PathVariable Long id) {
        return Result.ok(reportService.exportMarkdown(id));
    }

    /** 下载 PDF / XLSX（二进制流，不走统一 Result 包装） */
    @RequireRole
    @GetMapping("/{id}/file")
    public org.springframework.http.ResponseEntity<byte[]> file(@PathVariable Long id,
                                                               @RequestParam(defaultValue = "pdf") String format) {
        Map<String, Object> rendered = reportService.renderFile(id, format);
        byte[] bytes = (byte[]) rendered.get("bytes");
        String fileName = String.valueOf(rendered.get("fileName"));
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_TYPE,
                        String.valueOf(rendered.get("contentType")))
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + fileName + "\"")
                .body(bytes);
    }

    @RequireRole
    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable Long id) {
        reportService.delete(id);
        return Result.ok("已删除");
    }
}
