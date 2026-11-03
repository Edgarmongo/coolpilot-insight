package com.dy.comment.controller;

import com.dy.comment.annotation.RequireRole;
import com.dy.comment.config.AppProperties;
import com.dy.comment.dto.CollectRequest;
import com.dy.comment.dto.Result;
import com.dy.comment.service.CollectService;
import com.dy.comment.utils.CollectUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/collect")
public class CollectController {

    @Autowired
    private CollectService collectService;

    @Autowired
    private AppProperties appProperties;

    @RequireRole
    @PostMapping("/start")
    public Result<String> start(@RequestBody CollectRequest req) {
        String videoId = CollectUtils.extractVideoId(req.getVideoId());
        int max = req.getMaxComments();
        if (max < 100) max = 100;
        if (max > 1000) max = 1000;
        String mode = req.getCollectMode() == null ? "online" : req.getCollectMode();
        collectService.startCollect(videoId, max, mode);
        return Result.ok("采集任务已启动");
    }

    @RequireRole
    @GetMapping("/status")
    public Result<Map<String, String>> status(@RequestParam String videoId) {
        // 与 start 保持同一套 ID 提取规则，否则状态表的 key 对不上，永远查不到状态
        String key;
        try {
            key = CollectUtils.extractVideoId(videoId);
        } catch (Exception e) {
            key = videoId;
        }
        return Result.ok(collectService.getStatus(key));
    }

    /**
     * 本机导入：接收前端上传的 CSV 文件，落盘到采集数据目录（Datas），
     * 供「数据清洗」页面读取入库。文件以 <video_id>.csv 命名，
     * video_id 从文件名或查询参数中提取。
     */
    @RequireRole
    @PostMapping("/upload")
    public Result<List<Map<String, String>>> upload(@RequestParam("files") MultipartFile[] files,
                                                    @RequestParam(value = "videoId", required = false) String videoId) {
        List<Map<String, String>> saved = new ArrayList<>();
        File dir = new File(appProperties.getDatasDir());
        if (!dir.exists() && !dir.mkdirs()) {
            throw new RuntimeException("数据目录不存在且无法创建");
        }

        for (MultipartFile file : files) {
            if (file.isEmpty()) continue;
            String original = file.getOriginalFilename();
            String name = (original != null && !original.trim().isEmpty())
                    ? original.trim() : file.getName();
            // 文件名不是 .csv 时补全后缀
            if (!name.toLowerCase().endsWith(".csv")) name = name + ".csv";
            // 若文件名不是纯数字（不含 video_id），用传入的 videoId 或去掉扩展名
            String base = name.replaceAll("(?i)\\.csv$", "");
            if (!base.matches("\\d+") && videoId != null && videoId.matches("\\d+")) {
                base = videoId;
                name = base + ".csv";
            }
            File dest = new File(dir, name);
            try {
                file.transferTo(dest);
                java.util.Map<String, String> item = new java.util.LinkedHashMap<>();
                item.put("fileName", dest.getName());
                item.put("size", (dest.length() / 1024) + " KB");
                saved.add(item);
            } catch (Exception e) {
                throw new RuntimeException("保存文件失败: " + name + " - " + e.getMessage());
            }
        }
        return Result.ok(saved);
    }
}
