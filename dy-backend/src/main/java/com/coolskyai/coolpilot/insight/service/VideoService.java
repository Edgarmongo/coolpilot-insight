package com.coolskyai.coolpilot.insight.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.coolskyai.coolpilot.insight.entity.Video;
import com.coolskyai.coolpilot.insight.entity.VideoInsight;
import com.coolskyai.coolpilot.insight.mapper.CommentMapper;
import com.coolskyai.coolpilot.insight.mapper.VideoInsightMapper;
import com.coolskyai.coolpilot.insight.mapper.VideoMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 视频实体服务。对外 API 仍以平台 aweme_id 作为 videoId（前端契约不变），
 * 内部一律换算为 video.id（ADR-003）。
 */
@Service
public class VideoService {

    private final VideoMapper videoMapper;
    private final VideoInsightMapper insightMapper;
    private final CommentMapper commentMapper;
    private final GovernanceService governance;

    public VideoService(VideoMapper videoMapper, VideoInsightMapper insightMapper,
                        CommentMapper commentMapper, GovernanceService governance) {
        this.videoMapper = videoMapper;
        this.insightMapper = insightMapper;
        this.commentMapper = commentMapper;
        this.governance = governance;
    }

    public Video findByAwemeId(String awemeId) {
        if (awemeId == null || awemeId.isEmpty()) {
            return null;
        }
        return videoMapper.selectOne(new LambdaQueryWrapper<Video>()
                .eq(Video::getAwemeId, awemeId).last("LIMIT 1"));
    }

    public Video findById(Long id) {
        return id == null ? null : videoMapper.selectById(id);
    }

    /**
     * 把前端传入的 videoId（可能是 aweme_id，也可能是内部 id）解析为内部 id。
     * 先按 aweme_id 查，再回退按主键查。
     */
    public Long resolveInternalId(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String key = raw.trim();
        Video v = findByAwemeId(key);
        if (v != null) {
            return v.getId();
        }
        if (key.matches("\\d+")) {
            try {
                Video byId = videoMapper.selectById(Long.parseLong(key));
                if (byId != null) {
                    return byId.getId();
                }
            } catch (NumberFormatException ignored) {
                // 超长数字串不是内部 id
            }
        }
        return null;
    }

    /** 视频对外标识：优先返回 aweme_id，保证前端契约不变 */
    public String externalId(Long videoId) {
        Video v = videoMapper.selectById(videoId);
        return v == null ? null : v.getAwemeId();
    }

    public Video requireOrCreate(String awemeId) {
        Video v = findByAwemeId(awemeId);
        if (v != null) {
            return v;
        }
        Video created = new Video();
        created.setPlatform("douyin");
        created.setAwemeId(awemeId);
        created.setDiggCount(0L);
        created.setCommentCount(0L);
        created.setShareCount(0L);
        created.setStatus(1);
        videoMapper.insert(created);
        return created;
    }

    public void touchCollected(Long videoId) {
        Video patch = new Video();
        patch.setId(videoId);
        patch.setLastCollectedAt(java.time.LocalDateTime.now());
        videoMapper.updateById(patch);
    }

    /** 视频列表（含真实评论数与最近分析时间） */
    public List<Map<String, Object>> listForCurrentUser() {
        List<Long> allowed = governance.readableVideoIds();
        LambdaQueryWrapper<Video> wrapper = new LambdaQueryWrapper<Video>().orderByDesc(Video::getId);
        if (allowed != null) {
            if (allowed.isEmpty()) {
                return java.util.Collections.emptyList();
            }
            wrapper.in(Video::getId, allowed);
        }
        wrapper.last("LIMIT 200");
        List<Video> videos = videoMapper.selectList(wrapper);

        List<Map<String, Object>> out = new ArrayList<>(videos.size());
        for (Video v : videos) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("videoId", v.getAwemeId());
            row.put("title", v.getTitle() == null ? "" : v.getTitle());
            row.put("authorName", v.getAuthorName() == null ? "" : v.getAuthorName());
            row.put("awemeId", v.getAwemeId());

            Map<String, Object> sum = commentMapper.summaryRow(v.getId());
            long total = sum == null || sum.get("total") == null
                    ? 0L : ((Number) sum.get("total")).longValue();
            // 兼容三种既有前端字段名
            row.put("comments", total);
            row.put("commentCount", total);
            row.put("totalComments", total);

            VideoInsight last = insightMapper.selectOne(new LambdaQueryWrapper<VideoInsight>()
                    .eq(VideoInsight::getVideoId, v.getId())
                    .orderByDesc(VideoInsight::getId).last("LIMIT 1"));
            row.put("analyzedAt", last == null ? null : last.getCreatedAt());
            row.put("hasInsight", last != null);
            row.put("lastCollectedAt", v.getLastCollectedAt());
            out.add(row);
        }
        // 有数据的视频优先，其次按最近采集时间倒序
        out.sort((a, b) -> {
            int byComments = Long.compare(
                    ((Number) b.get("comments")).longValue(),
                    ((Number) a.get("comments")).longValue());
            if (byComments != 0) {
                return byComments;
            }
            Object ta = a.get("lastCollectedAt");
            Object tb = b.get("lastCollectedAt");
            if (ta == null && tb == null) {
                return 0;
            }
            if (ta == null) {
                return 1;
            }
            if (tb == null) {
                return -1;
            }
            return tb.toString().compareTo(ta.toString());
        });
        return out;
    }
}
