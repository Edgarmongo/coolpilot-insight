package com.coolskyai.coolpilot.insight.service.impl;

import com.coolskyai.coolpilot.insight.config.AppProperties;
import com.coolskyai.coolpilot.insight.interceptor.RequestContext;
import com.coolskyai.coolpilot.insight.service.CleanService;
import com.coolskyai.coolpilot.insight.service.GovernanceService;
import com.coolskyai.coolpilot.insight.service.VideoService;
import com.coolskyai.coolpilot.insight.utils.MemoryCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class CleanServiceImpl implements CleanService {

    private static final Logger log = LoggerFactory.getLogger(CleanServiceImpl.class);
    /** 文件名白名单：只允许字母数字点下划线连字符，彻底阻断 ../ 与绝对路径 */
    private static final Pattern SAFE_NAME = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");
    private static final Pattern AWEME_FILE = Pattern.compile("^(\\d{6,})\\.csv$");

    @Autowired
    private AppProperties appProperties;

    @Autowired
    private MemoryCache cache;

    @Autowired
    private GovernanceService governance;

    @Autowired
    private VideoService videoService;

    @Override
    public List<Map<String, String>> getPendingFiles() {
        List<Map<String, String>> files = new ArrayList<>();
        File dir = new File(appProperties.getDatasDir());
        if (!dir.exists()) {
            return files;
        }
        File[] csvFiles = dir.listFiles((d, name) -> name.endsWith(".csv"));
        if (csvFiles == null) {
            return files;
        }
        boolean admin = governance.isAdmin();
        for (File f : csvFiles) {
            if (!admin && !canAccessFile(f.getName())) {
                continue;
            }
            Map<String, String> item = new LinkedHashMap<>();
            item.put("fileName", f.getName());
            item.put("size", (f.length() / 1024) + " KB");
            files.add(item);
        }
        return files;
    }

    @Override
    public Map<String, Object> runClean(List<String> fileNames) {
        return runClean(fileNames, RequestContext.getUserId());
    }

    @Override
    public Map<String, Object> runClean(List<String> fileNames, Long operatorUserId) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (fileNames == null || fileNames.isEmpty()) {
            result.put("success", false);
            result.put("output", "未指定文件");
            return result;
        }
        List<String> safeNames = new ArrayList<>();
        for (String name : fileNames) {
            String base = baseName(name);
            if (base == null) {
                result.put("success", false);
                result.put("output", "非法文件名: " + name);
                return result;
            }
            if (!governance.isAdmin() && !canAccessFile(base)) {
                result.put("success", false);
                result.put("output", "无权处理文件: " + base);
                return result;
            }
            safeNames.add(base);
        }

        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(appProperties.getPythonExe());
            cmd.add("clean_main.py");
            cmd.addAll(safeNames);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(appProperties.getPythonCleanDir()));
            pb.redirectErrorStream(true);
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            if (operatorUserId != null) {
                pb.environment().put("CLEAN_USER_ID", operatorUserId.toString());
            }
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            int exitCode = process.waitFor();
            result.put("success", exitCode == 0);
            result.put("output", output.toString());
            cache.deleteByPrefix("analysis:");

            // 清洗成功后标记视频最近采集时间
            for (String name : safeNames) {
                java.util.regex.Matcher m = AWEME_FILE.matcher(name);
                if (m.matches()) {
                    Long vid = videoService.resolveInternalId(m.group(1));
                    if (vid != null) {
                        videoService.touchCollected(vid);
                    }
                }
            }
        } catch (Exception e) {
            log.error("[clean] 执行失败", e);
            result.put("success", false);
            result.put("output", "清洗失败: " + e.getMessage());
        }
        return result;
    }

    @Override
    public byte[] exportFiles(List<String> fileNames) {
        if (fileNames == null || fileNames.isEmpty()) {
            return new byte[0];
        }
        List<Path> paths = new ArrayList<>();
        for (String name : fileNames) {
            String base = baseName(name);
            if (base == null) {
                throw new SecurityException("非法文件名: " + name);
            }
            if (!governance.isAdmin() && !canAccessFile(base)) {
                throw new SecurityException("无权导出文件: " + base);
            }
            Path p = safeResolve(base);
            if (p == null || !Files.exists(p)) {
                throw new IllegalArgumentException("文件不存在: " + base);
            }
            paths.add(p);
        }
        try {
            if (paths.size() == 1) {
                return Files.readAllBytes(paths.get(0));
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(bos)) {
                for (Path p : paths) {
                    zos.putNextEntry(new ZipEntry(p.getFileName().toString()));
                    Files.copy(p, zos);
                    zos.closeEntry();
                }
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("导出失败: " + e.getMessage());
        }
    }

    /** 取文件名部分并校验，拒绝任何路径成分 */
    private String baseName(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.trim();
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            return null;
        }
        return SAFE_NAME.matcher(name).matches() ? name : null;
    }

    /** 规范化后必须仍在 Datas 目录内 */
    private Path safeResolve(String base) {
        try {
            Path root = Paths.get(appProperties.getDatasDir()).toAbsolutePath().normalize();
            Path target = root.resolve(base).normalize();
            return target.startsWith(root) ? target : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 非管理员只能访问自己有权读取的视频文件 */
    private boolean canAccessFile(String base) {
        java.util.regex.Matcher m = AWEME_FILE.matcher(base);
        if (!m.matches()) {
            return false;
        }
        Long vid = videoService.resolveInternalId(m.group(1));
        if (vid == null) {
            // 尚未入库的视频：允许上传者后续清洗，归属由上传接口记录
            return true;
        }
        return governance.canReadComments(vid);
    }
}
