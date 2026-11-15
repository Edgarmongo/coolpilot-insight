package com.coolpilot.insight.collection.controller;

import com.coolpilot.insight.common.security.RequireRole;
import com.coolpilot.insight.integration.config.AppProperties;
import com.coolpilot.insight.dto.CollectRequest;
import com.coolpilot.insight.common.web.Result;
import com.coolpilot.insight.common.security.RequestContext;
import com.coolpilot.insight.collection.application.CollectService;
import com.coolpilot.insight.collection.application.RunApplicationService;
import com.coolpilot.insight.collection.domain.ColRun;
import com.coolpilot.insight.utils.CollectUtils;
import com.coolpilot.insight.system.application.AuditService;
import com.coolpilot.insight.common.exception.BizException;
import com.coolpilot.insight.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;
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

    @org.springframework.beans.factory.annotation.Autowired
    private RunApplicationService runApplicationService;


    private static final Pattern LEGAL_CSV = Pattern.compile("^\\d{6,}\\.csv$");

    @Autowired
    private CollectService collectService;

    @Autowired
    private AppProperties appProperties;

    @Autowired
    private AuditService auditService;

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
        auditService.audit("SUBMIT_COLLECTION", "COLLECTION_TASK",
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
            throw new BizException(ErrorCode.INTERNAL, "数据目录无法创建: " + e.getMessage());
        }

        List<Map<String, String>> saved = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            String original = file.getOriginalFilename();
            String name = original == null ? "" : original.trim();
            if (name.contains("/") || name.contains("\\") || name.contains("..")) {
                throw new BizException(ErrorCode.PARAM_INVALID, "非法文件名: " + name);
            }
            String base = name.replaceAll("(?i)\\.csv$", "");
            if (!base.matches("\\d{6,}") && videoId != null && videoId.matches("\\d{6,}")) {
                base = videoId;
            }
            String finalName = base + ".csv";
            if (!LEGAL_CSV.matcher(finalName).matches()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "文件必须以视频编号命名，例如 7703721523349239951.csv");
            }
            Path dest = root.resolve(finalName).normalize();
            if (!dest.startsWith(root)) {
                throw new BizException(ErrorCode.PARAM_INVALID, "非法目标路径");
            }
            try {
                file.transferTo(dest.toFile());
                Map<String, String> item = new LinkedHashMap<>();
                item.put("fileName", finalName);
                item.put("size", (Files.size(dest) / 1024) + " KB");
                saved.add(item);
                auditService.audit("UPLOAD_CSV", "FILE", null, finalName);
            } catch (Exception e) {
                throw new BizException(ErrorCode.INTERNAL, "保存文件失败: " + finalName + " - " + e.getMessage());
            }
        }
        return Result.ok(saved);
    }

    // ---------------------------------------------------------------- Run

    /** 手动触发一次运行。与定时调度共用同一应用服务。 */
    @PostMapping("/tasks/{id}/trigger")
    public Result<ColRun> trigger(@PathVariable Long id) {
        Long userId = com.coolpilot.insight.common.security.RequestContext.getUserId();
        return Result.ok(runApplicationService.trigger(id, "MANUAL", userId));
    }

    /** 该任务最近一次运行（Task 列表展示"最近运行时间"用）。 */
    @GetMapping("/tasks/{id}/runs/latest")
    public Result<ColRun> latestRun(@PathVariable Long id) {
        return Result.ok(runApplicationService.latestOf(id));
    }

    @GetMapping("/tasks/{id}/runs")
    public Result<List<ColRun>> listRuns(@PathVariable Long id,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return Result.ok(collectService.listRuns(id, page, size));
    }

    @GetMapping("/runs/{runId}")
    public Result<ColRun> runDetail(@PathVariable Long runId) {
        return Result.ok(collectService.runDetail(runId));
    }
}
