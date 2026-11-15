package com.coolskyai.coolpilot.insight.controller;

import com.coolskyai.coolpilot.insight.annotation.RequireRole;
import com.coolskyai.coolpilot.insight.config.AppProperties;
import com.coolskyai.coolpilot.insight.dto.CollectRequest;
import com.coolskyai.coolpilot.insight.dto.Result;
import com.coolskyai.coolpilot.insight.interceptor.RequestContext;
import com.coolskyai.coolpilot.insight.service.CollectService;
import com.coolskyai.coolpilot.insight.service.GovernanceService;
import com.coolskyai.coolpilot.insight.utils.CollectUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/collect")
public class CollectController {

    private static final Pattern LEGAL_CSV = Pattern.compile("^\\d{6,}\\.csv$");

    @Autowired
    private CollectService collectService;

    @Autowired
    private AppProperties appProperties;

    @Autowired
    private GovernanceService governance;

    @RequireRole
    @PostMapping("/start")
    public Result<Map<String, Object>> start(@RequestBody CollectRequest req) {
        List<String> inputs = new ArrayList<>();
        if (req.getVideoIds() != null && !req.getVideoIds().isEmpty()) {
            inputs.addAll(req.getVideoIds());
        }
        if (req.getVideoId() != null && !req.getVideoId().trim().isEmpty()) {
            // 支持多行/逗号分隔
            for (String part : req.getVideoId().split("[\\r\\n,;]+")) {
                if (!part.trim().isEmpty()) {
                    inputs.add(part.trim());
                }
            }
        }
        int max = Math.min(Math.max(req.getMaxComments(), 1), 1000);
        String mode = req.getCollectMode() == null ? "online" : req.getCollectMode();
        Map<String, Object> result = collectService.submit(
                inputs, max, mode, RequestContext.getUserId());
        governance.audit("SUBMIT_COLLECTION", "COLLECTION_TASK",
                result.get("taskId") instanceof Number ? ((Number) result.get("taskId")).longValue() : null,
                "items=" + result.get("totalItems"));
        return Result.ok(result);
    }

    @RequireRole
    @GetMapping("/status")
    public Result<Map<String, String>> status(@RequestParam String videoId) {
        String key;
        try {
            key = CollectUtils.extractVideoId(videoId);
        } catch (Exception e) {
            key = videoId;
        }
        return Result.ok(collectService.getStatus(key));
    }

    // ------------------------------------------------------------ 任务中心

    @RequireRole
    @GetMapping("/tasks")
    public Result<List<Map<String, Object>>> tasks(@RequestParam(required = false) String status,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return Result.ok(collectService.listTasks(status, page, size));
    }

    @RequireRole
    @GetMapping("/tasks/{id}")
    public Result<Map<String, Object>> taskDetail(@PathVariable Long id) {
        return Result.ok(collectService.getTaskDetail(id));
    }

    @RequireRole
    @GetMapping("/tasks/{id}/logs")
    public Result<List<Map<String, Object>>> taskLogs(@PathVariable Long id) {
        return Result.ok(collectService.getTaskLogs(id));
    }

    @RequireRole
    @PostMapping("/tasks/{id}/cancel")
    public Result<String> cancel(@PathVariable Long id) {
        collectService.cancel(id);
        return Result.ok("已请求取消");
    }

    @RequireRole
    @PostMapping("/tasks/{id}/retry")
    public Result<String> retry(@PathVariable Long id) {
        collectService.retry(id);
        return Result.ok("已重新入队");
    }

    // ------------------------------------------------------------ 本机导入

    /**
     * 本机导入：接收 CSV 落盘到 Datas 目录，供清洗入库。
     * 文件名强校验为 {@code <aweme_id>.csv}，彻底阻断路径穿越。
     */
    @RequireRole
    @PostMapping("/upload")
    public Result<List<Map<String, String>>> upload(@RequestParam("files") MultipartFile[] files,
                                                    @RequestParam(value = "videoId", required = false) String videoId) {
        Path root = Paths.get(appProperties.getDatasDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (Exception e) {
            throw new IllegalStateException("数据目录无法创建: " + e.getMessage());
        }

        List<Map<String, String>> saved = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            String original = file.getOriginalFilename();
            String name = original == null ? "" : original.trim();
            if (name.contains("/") || name.contains("\\") || name.contains("..")) {
                throw new SecurityException("非法文件名: " + name);
            }
            String base = name.replaceAll("(?i)\\.csv$", "");
            if (!base.matches("\\d{6,}") && videoId != null && videoId.matches("\\d{6,}")) {
                base = videoId;
            }
            String finalName = base + ".csv";
            if (!LEGAL_CSV.matcher(finalName).matches()) {
                throw new IllegalArgumentException("文件必须以视频编号命名，例如 7703721523349239951.csv");
            }
            Path dest = root.resolve(finalName).normalize();
            if (!dest.startsWith(root)) {
                throw new SecurityException("非法目标路径");
            }
            try {
                file.transferTo(dest.toFile());
                Map<String, String> item = new LinkedHashMap<>();
                item.put("fileName", finalName);
                item.put("size", (Files.size(dest) / 1024) + " KB");
                saved.add(item);
                governance.audit("UPLOAD_CSV", "FILE", null, finalName);
            } catch (Exception e) {
                throw new IllegalStateException("保存文件失败: " + finalName + " - " + e.getMessage());
            }
        }
        return Result.ok(saved);
    }
}
