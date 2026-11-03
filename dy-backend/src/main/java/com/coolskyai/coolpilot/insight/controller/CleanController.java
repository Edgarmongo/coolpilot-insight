package com.coolskyai.coolpilot.insight.controller;

import com.coolskyai.coolpilot.insight.annotation.RequireRole;
import com.coolskyai.coolpilot.insight.dto.Result;
import com.coolskyai.coolpilot.insight.service.CleanService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/clean")
public class CleanController {

    @Autowired
    private CleanService cleanService;

    @RequireRole
    @GetMapping("/files")
    public Result<List<Map<String, String>>> getFiles() {
        return Result.ok(cleanService.getPendingFiles());
    }

    @RequireRole
    @PostMapping("/run")
    public Result<Map<String, Object>> run(@RequestBody List<String> fileNames) {
        return Result.ok(cleanService.runClean(fileNames));
    }

    @RequireRole
    @PostMapping("/export")
    public ResponseEntity<?> export(@RequestBody List<String> fileNames) {
        byte[] data = cleanService.exportFiles(fileNames);
        if (data.length == 0) return ResponseEntity.badRequest().body("未选择文件");
        String filename = fileNames.size() == 1 ? fileNames.get(0) : "datas_export.zip";
        String contentType = fileNames.size() == 1 ? "text/csv" : "application/zip";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                .contentType(MediaType.parseMediaType(contentType))
                .body(data);
    }
}
