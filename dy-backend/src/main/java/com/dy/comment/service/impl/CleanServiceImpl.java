package com.dy.comment.service.impl;

import com.dy.comment.config.AppProperties;
import com.dy.comment.interceptor.RequestContext;
import com.dy.comment.service.CleanService;
import com.dy.comment.utils.MemoryCache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class CleanServiceImpl implements CleanService {

    @Autowired
    private AppProperties appProperties;

    @Autowired
    private MemoryCache cache;

    @Override
    public List<Map<String, String>> getPendingFiles() {
        List<Map<String, String>> files = new ArrayList<>();
        File dir = new File(appProperties.getDatasDir());
        if (!dir.exists()) return files;
        File[] csvFiles = dir.listFiles((d, name) -> name.endsWith(".csv"));
        if (csvFiles != null) {
            for (File f : csvFiles) {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("fileName", f.getName());
                item.put("size", (f.length() / 1024) + " KB");
                files.add(item);
            }
        }
        return files;
    }

    @Override
    public Map<String, Object> runClean(List<String> fileNames) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(appProperties.getPythonExe());
            cmd.add("clean_main.py");
            cmd.addAll(fileNames);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(appProperties.getPythonCleanDir()));
            pb.redirectErrorStream(true);
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            Long uid = RequestContext.getUserId();
            if (uid != null) pb.environment().put("CLEAN_USER_ID", uid.toString());
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append("\n");
            }
            int exitCode = process.waitFor();
            result.put("success", exitCode == 0);
            result.put("output", output.toString());
            cache.deleteByPrefix("analysis:");
        } catch (Exception e) {
            result.put("success", false);
            result.put("output", e.getMessage());
        }
        return result;
    }

    @Override
    public byte[] exportFiles(List<String> fileNames) {
        if (fileNames == null || fileNames.isEmpty()) return new byte[0];
        try {
            if (fileNames.size() == 1) {
                File f = new File(appProperties.getDatasDir(), fileNames.get(0));
                if (!f.exists()) throw new RuntimeException("文件不存在");
                return Files.readAllBytes(f.toPath());
            }
            // 多文件打包 zip
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(bos)) {
                for (String name : fileNames) {
                    File f = new File(appProperties.getDatasDir(), name);
                    if (f.exists()) {
                        zos.putNextEntry(new ZipEntry(name));
                        Files.copy(f.toPath(), zos);
                        zos.closeEntry();
                    }
                }
            }
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("导出失败: " + e.getMessage());
        }
    }
}
