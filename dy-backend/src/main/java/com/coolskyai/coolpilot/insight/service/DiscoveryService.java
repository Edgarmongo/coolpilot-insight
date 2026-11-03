package com.coolskyai.coolpilot.insight.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.coolskyai.coolpilot.insight.config.AppProperties;
import com.coolskyai.coolpilot.insight.entity.*;
import com.coolskyai.coolpilot.insight.mapper.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 关键词发现：调用 Python 搜索脚本解析抖音搜索结果，落 video / keyword_video / discovery_task_item。
 *
 * <p>边界：发现任务**不会**自动触发采集任务——避免「搜一次就烧一堆采集资源」。
 */
@Service
public class DiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryService.class);
    private static final Pattern VIDEO_LINE = Pattern.compile("^VIDEO\\|([^|]+)\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|([^|]*)$");

    private final KeywordMapper keywordMapper;
    private final DiscoveryTaskMapper taskMapper;
    private final DiscoveryTaskItemMapper itemMapper;
    private final VideoMapper videoMapper;
    private final KeywordVideoMapper keywordVideoMapper;
    private final VideoService videoService;
    private final GovernanceService governance;
    private final AppProperties appProperties;

    public DiscoveryService(KeywordMapper keywordMapper, DiscoveryTaskMapper taskMapper,
                            DiscoveryTaskItemMapper itemMapper, VideoMapper videoMapper,
                            KeywordVideoMapper keywordVideoMapper, VideoService videoService,
                            GovernanceService governance, AppProperties appProperties) {
        this.keywordMapper = keywordMapper;
        this.taskMapper = taskMapper;
        this.itemMapper = itemMapper;
        this.videoMapper = videoMapper;
        this.keywordVideoMapper = keywordVideoMapper;
        this.videoService = videoService;
        this.governance = governance;
        this.appProperties = appProperties;
    }

    // ---------------------------------------------------------- 关键词 CRUD

    public Map<String, Object> createKeyword(String keyword, String description) {
        String raw = keyword == null ? "" : keyword.trim();
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("关键词不能为空");
        }
        if (raw.length() > 128) {
            throw new IllegalArgumentException("关键词最长 128 字符");
        }
        String normalized = normalize(raw);
        Long owner = governance.currentUserId();
        Keyword exist = keywordMapper.selectOne(new LambdaQueryWrapper<Keyword>()
                .eq(Keyword::getOwnerUserId, owner)
                .eq(Keyword::getNormalized, normalized).last("LIMIT 1"));
        if (exist != null) {
            return row(exist);
        }
        Keyword k = new Keyword();
        k.setKeyword(raw);
        k.setNormalized(normalized);
        k.setDescription(description);
        k.setOwnerUserId(owner);
        k.setStatus(1);
        keywordMapper.insert(k);
        governance.audit("CREATE_KEYWORD", "KEYWORD", k.getId(), null);
        return row(k);
    }

    /** 归一化：小写 + 去空白 + 全角转半角 */
    public static String normalize(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.trim().toCharArray()) {
            if (c == '\u3000') {
                continue;
            }
            if (c >= '\uFF01' && c <= '\uFF5E') {
                sb.append((char) (c - 0xFEE0));
            } else {
                sb.append(c);
            }
        }
        return sb.toString().toLowerCase().replaceAll("\\s+", "");
    }

    public List<Map<String, Object>> listKeywords() {
        LambdaQueryWrapper<Keyword> wrapper = new LambdaQueryWrapper<Keyword>().orderByDesc(Keyword::getId);
        if (!governance.isAdmin()) {
            wrapper.eq(Keyword::getOwnerUserId, governance.currentUserId());
        }
        return keywordMapper.selectList(wrapper).stream().map(this::row).collect(Collectors.toList());
    }

    public void deleteKeyword(Long id) {
        Keyword k = keywordMapper.selectById(id);
        if (k == null) {
            throw new IllegalArgumentException("关键词不存在");
        }
        if (!governance.isAdmin() && !Objects.equals(k.getOwnerUserId(), governance.currentUserId())) {
            throw new SecurityException("无权删除该关键词");
        }
        k.setStatus(0);
        keywordMapper.updateById(k);
        governance.audit("DISABLE_KEYWORD", "KEYWORD", id, null);
    }

    private Map<String, Object> row(Keyword k) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", k.getId());
        m.put("keyword", k.getKeyword());
        m.put("normalized", k.getNormalized());
        m.put("description", k.getDescription());
        m.put("status", k.getStatus());
        m.put("createdAt", k.getCreatedAt());
        m.put("videoCount", keywordVideoMapper.selectCount(new LambdaQueryWrapper<KeywordVideo>()
                .eq(KeywordVideo::getKeywordId, k.getId())));
        return m;
    }

    // ---------------------------------------------------------- 发现任务

    public Map<String, Object> submitDiscovery(Long keywordId, int max, Map<String, Object> params) {
        Keyword k = keywordMapper.selectById(keywordId);
        if (k == null) {
            throw new IllegalArgumentException("关键词不存在");
        }
        if (!governance.isAdmin() && !Objects.equals(k.getOwnerUserId(), governance.currentUserId())) {
            throw new SecurityException("无权使用该关键词");
        }
        DiscoveryTask task = new DiscoveryTask();
        task.setTaskNo("DT" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%03d", new Random().nextInt(1000)));
        task.setKeywordId(keywordId);
        task.setStatus("PENDING");
        task.setOwnerUserId(governance.currentUserId());
        task.setRequestedMax(Math.min(Math.max(max, 1), 200));
        task.setFoundCount(0);
        task.setNewVideoCount(0);
        task.setParamsJson(params == null ? null : JSON.toJSONString(params));
        taskMapper.insert(task);

        Long taskId = task.getId();
        new Thread(() -> run(taskId), "discovery-worker").start();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", taskId);
        out.put("taskNo", task.getTaskNo());
        out.put("message", "发现任务已提交");
        return out;
    }

    private void run(Long taskId) {
        DiscoveryTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        task.setStatus("RUNNING");
        task.setStartedAt(LocalDateTime.now());
        taskMapper.updateById(task);

        Keyword k = keywordMapper.selectById(task.getKeywordId());
        try {
            List<VideoHit> hits = invokeSearch(k.getKeyword(), task.getRequestedMax());
            int position = 0;
            int newCount = 0;
            for (VideoHit hit : hits) {
                position++;
                Video v = videoService.findByAwemeId(hit.awemeId);
                boolean isNew = false;
                if (v == null) {
                    v = new Video();
                    v.setPlatform("douyin");
                    v.setAwemeId(hit.awemeId);
                    v.setTitle(hit.title);
                    v.setAuthorName(hit.author);
                    v.setDiggCount(hit.likes);
                    v.setCommentCount(0L);
                    v.setShareCount(0L);
                    v.setStatus(1);
                    videoMapper.insert(v);
                    isNew = true;
                    newCount++;
                } else if (v.getTitle() == null && hit.title != null) {
                    v.setTitle(hit.title);
                    videoMapper.updateById(v);
                }

                Long exists = itemMapper.selectCount(new LambdaQueryWrapper<DiscoveryTaskItem>()
                        .eq(DiscoveryTaskItem::getTaskId, taskId)
                        .eq(DiscoveryTaskItem::getVideoId, v.getId()));
                if (exists == null || exists == 0) {
                    DiscoveryTaskItem item = new DiscoveryTaskItem();
                    item.setTaskId(taskId);
                    item.setVideoId(v.getId());
                    item.setPosition(position);
                    item.setIsNew(isNew ? 1 : 0);
                    itemMapper.insert(item);
                }

                Long linked = keywordVideoMapper.selectCount(new LambdaQueryWrapper<KeywordVideo>()
                        .eq(KeywordVideo::getKeywordId, k.getId())
                        .eq(KeywordVideo::getVideoId, v.getId()));
                if (linked == null || linked == 0) {
                    KeywordVideo kv = new KeywordVideo();
                    kv.setKeywordId(k.getId());
                    kv.setVideoId(v.getId());
                    keywordVideoMapper.insert(kv);
                }
            }
            task.setStatus("SUCCESS");
            task.setFoundCount(hits.size());
            task.setNewVideoCount(newCount);
            task.setFinishedAt(LocalDateTime.now());
            taskMapper.updateById(task);
        } catch (Exception e) {
            log.error("[discovery] 任务 {} 失败: {}", taskId, e.getMessage());
            task.setStatus("FAILED");
            task.setErrorMessage(e.getMessage());
            task.setFinishedAt(LocalDateTime.now());
            taskMapper.updateById(task);
        }
    }

    private static class VideoHit {
        String awemeId;
        String title;
        String author;
        long likes;
    }

    /**
     * 调用 Python 搜索脚本，按行解析输出：
     * {@code VIDEO|<aweme_id>|<title>|<author>|<likes>|<url>}
     */
    private List<VideoHit> invokeSearch(String keyword, int max) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(appProperties.getPythonExe());
        cmd.add("douyin_search.py");
        cmd.add(keyword);
        cmd.add(String.valueOf(max));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new java.io.File(appProperties.getPythonDataDir()));
        pb.redirectErrorStream(true);
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        Process process = pb.start();

        List<VideoHit> hits = new ArrayList<>();
        StringBuilder tail = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("VIDEO|")) {
                    Matcher m = VIDEO_LINE.matcher(line);
                    if (m.matches()) {
                        VideoHit h = new VideoHit();
                        h.awemeId = m.group(1).trim();
                        h.title = m.group(2).trim();
                        h.author = m.group(3).trim();
                        try {
                            h.likes = Long.parseLong(m.group(4).trim());
                        } catch (NumberFormatException ignored) {
                            h.likes = 0L;
                        }
                        if (h.awemeId.matches("\\d{6,}")) {
                            hits.add(h);
                        }
                    }
                } else {
                    tail.append(line).append("\n");
                    if (tail.length() > 2000) {
                        tail.delete(0, tail.length() - 1000);
                    }
                }
            }
        }
        int exit = process.waitFor();
        if (exit != 0 && hits.isEmpty()) {
            throw new IllegalStateException("搜索脚本执行失败: " + tail.toString().trim());
        }
        return hits;
    }

    // ---------------------------------------------------------- 查询

    public List<Map<String, Object>> listKeywordVideos(Long keywordId) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<KeywordVideo> links = keywordVideoMapper.selectList(new LambdaQueryWrapper<KeywordVideo>()
                .eq(KeywordVideo::getKeywordId, keywordId).orderByDesc(KeywordVideo::getId).last("LIMIT 200"));
        for (KeywordVideo link : links) {
            Video v = videoMapper.selectById(link.getVideoId());
            if (v == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("videoId", v.getAwemeId());
            m.put("title", v.getTitle());
            m.put("authorName", v.getAuthorName());
            m.put("diggCount", v.getDiggCount());
            m.put("discoveredAt", link.getDiscoveredAt());
            Map<String, Object> sum = null;
            m.put("comments", 0);
            out.add(m);
        }
        return out;
    }

    public List<Map<String, Object>> listTasks() {
        LambdaQueryWrapper<DiscoveryTask> wrapper = new LambdaQueryWrapper<DiscoveryTask>()
                .orderByDesc(DiscoveryTask::getId);
        if (!governance.isAdmin()) {
            wrapper.eq(DiscoveryTask::getOwnerUserId, governance.currentUserId());
        }
        wrapper.last("LIMIT 100");
        return taskMapper.selectList(wrapper).stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("taskNo", t.getTaskNo());
            m.put("keywordId", t.getKeywordId());
            m.put("status", t.getStatus());
            m.put("foundCount", t.getFoundCount());
            m.put("newVideoCount", t.getNewVideoCount());
            m.put("createdAt", t.getCreatedAt());
            m.put("finishedAt", t.getFinishedAt());
            m.put("errorMessage", t.getErrorMessage());
            return m;
        }).collect(Collectors.toList());
    }
}
