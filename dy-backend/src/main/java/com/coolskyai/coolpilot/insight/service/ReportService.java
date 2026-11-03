package com.coolskyai.coolpilot.insight.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.coolskyai.coolpilot.insight.entity.Report;
import com.coolskyai.coolpilot.insight.entity.ReportVersion;
import com.coolskyai.coolpilot.insight.entity.VideoInsight;
import com.coolskyai.coolpilot.insight.mapper.ReportMapper;
import com.coolskyai.coolpilot.insight.mapper.ReportVersionMapper;
import com.coolskyai.coolpilot.insight.mapper.VideoInsightMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 报告中心：基于已有 video_insight 生成版本化报告。
 * 报告不重新调用 AI——避免重复付费，只做结构化归档与导出。
 */
@Service
public class ReportService {

    private final ReportMapper reportMapper;
    private final ReportVersionMapper versionMapper;
    private final VideoInsightMapper insightMapper;
    private final VideoService videoService;
    private final GovernanceService governance;

    public ReportService(ReportMapper reportMapper, ReportVersionMapper versionMapper,
                         VideoInsightMapper insightMapper, VideoService videoService,
                         GovernanceService governance) {
        this.reportMapper = reportMapper;
        this.versionMapper = versionMapper;
        this.insightMapper = insightMapper;
        this.videoService = videoService;
        this.governance = governance;
    }

    /** 用某视频最新洞察生成报告 */
    public Map<String, Object> createFromVideo(String videoId, String title) {
        Long vid = videoService.resolveInternalId(videoId);
        if (vid == null) {
            throw new IllegalArgumentException("视频不存在");
        }
        governance.requireReadComments(vid);

        VideoInsight insight = insightMapper.selectOne(new LambdaQueryWrapper<VideoInsight>()
                .eq(VideoInsight::getVideoId, vid)
                .orderByDesc(VideoInsight::getId).last("LIMIT 1"));
        if (insight == null) {
            throw new IllegalStateException("该视频还没有洞察结果，请先生成视频级洞察");
        }

        Report report = new Report();
        report.setReportNo("RP" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%03d", new Random().nextInt(1000)));
        report.setTitle(title == null || title.trim().isEmpty()
                ? "视频洞察报告 · " + videoId : title.trim());
        report.setReportType("VIDEO");
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("videoId", videoId);
        scope.put("insightId", insight.getId());
        scope.put("sampleCount", insight.getSampleCount());
        scope.put("generatedAt", LocalDateTime.now().toString());
        report.setScopeJson(JSON.toJSONString(scope));
        report.setOwnerUserId(governance.currentUserId());
        report.setStatus("PUBLISHED");
        report.setCurrentVersion(1);
        reportMapper.insert(report);

        ReportVersion version = new ReportVersion();
        version.setReportId(report.getId());
        version.setVersionNo(1);
        version.setContentJson(insight.getResultJson());
        version.setContentMd(toMarkdown(report.getTitle(), scope, insight));
        version.setAnalysisTaskId(insight.getAnalysisTaskId());
        version.setEvidenceCount(insight.getEvidenceCount() == null ? 0 : insight.getEvidenceCount());
        version.setGeneratedBy(governance.currentUserId());
        versionMapper.insert(version);

        governance.audit("CREATE_REPORT", "REPORT", report.getId(), report.getReportType());
        return detail(report.getId());
    }

    public List<Map<String, Object>> list() {
        LambdaQueryWrapper<Report> wrapper = new LambdaQueryWrapper<Report>()
                .isNull(Report::getDeletedAt).orderByDesc(Report::getId);
        if (!governance.isAdmin()) {
            wrapper.eq(Report::getOwnerUserId, governance.currentUserId());
        }
        wrapper.last("LIMIT 100");
        return reportMapper.selectList(wrapper).stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("reportNo", r.getReportNo());
            m.put("title", r.getTitle());
            m.put("reportType", r.getReportType());
            m.put("status", r.getStatus());
            m.put("currentVersion", r.getCurrentVersion());
            m.put("createdAt", r.getCreatedAt());
            return m;
        }).collect(java.util.stream.Collectors.toList());
    }

    public Map<String, Object> detail(Long id) {
        Report report = requireReport(id);
        ReportVersion latest = versionMapper.selectOne(new LambdaQueryWrapper<ReportVersion>()
                .eq(ReportVersion::getReportId, id)
                .orderByDesc(ReportVersion::getVersionNo).last("LIMIT 1"));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", report.getId());
        m.put("reportNo", report.getReportNo());
        m.put("title", report.getTitle());
        m.put("reportType", report.getReportType());
        m.put("status", report.getStatus());
        m.put("currentVersion", report.getCurrentVersion());
        m.put("createdAt", report.getCreatedAt());
        m.put("scope", JSON.parseObject(report.getScopeJson()));
        if (latest != null) {
            m.put("versionNo", latest.getVersionNo());
            m.put("contentMd", latest.getContentMd());
            m.put("content", JSON.parseObject(latest.getContentJson()));
            m.put("evidenceCount", latest.getEvidenceCount());
            m.put("generatedAt", latest.getGeneratedAt());
        }
        return m;
    }

    public List<Map<String, Object>> versions(Long id) {
        requireReport(id);
        return versionMapper.selectList(new LambdaQueryWrapper<ReportVersion>()
                        .eq(ReportVersion::getReportId, id).orderByDesc(ReportVersion::getVersionNo))
                .stream().map(v -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("versionNo", v.getVersionNo());
                    m.put("evidenceCount", v.getEvidenceCount());
                    m.put("generatedAt", v.getGeneratedAt());
                    return m;
                }).collect(java.util.stream.Collectors.toList());
    }

    /** 导出 Markdown（Excel/PDF 导出留待后续版本） */
    public Map<String, Object> exportMarkdown(Long id) {
        requireReport(id);
        ReportVersion latest = versionMapper.selectOne(new LambdaQueryWrapper<ReportVersion>()
                .eq(ReportVersion::getReportId, id)
                .orderByDesc(ReportVersion::getVersionNo).last("LIMIT 1"));
        if (latest == null) {
            throw new IllegalStateException("报告没有内容");
        }
        Report report = reportMapper.selectById(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fileName", report.getReportNo() + ".md");
        out.put("content", latest.getContentMd() == null ? "" : latest.getContentMd());
        governance.audit("EXPORT_REPORT", "REPORT", id, "markdown");
        return out;
    }

    public void delete(Long id) {
        Report report = requireReport(id);
        report.setDeletedAt(LocalDateTime.now());
        reportMapper.updateById(report);
        governance.audit("DELETE_REPORT", "REPORT", id, null);
    }

    private Report requireReport(Long id) {
        Report report = reportMapper.selectById(id);
        if (report == null || report.getDeletedAt() != null) {
            throw new IllegalArgumentException("报告不存在");
        }
        if (!governance.isAdmin() && !Objects.equals(report.getOwnerUserId(), governance.currentUserId())) {
            throw new SecurityException("无权访问该报告");
        }
        return report;
    }

    /** 把结构化洞察渲染成可读 Markdown（渲染用，非权威） */
    private String toMarkdown(String title, Map<String, Object> scope, VideoInsight insight) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append("\n\n");
        sb.append("> 生成时间：")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append(" ｜ 样本评论：").append(insight.getSampleCount())
                .append(" 条 ｜ 证据引用：").append(insight.getEvidenceCount()).append(" 条\n\n");
        sb.append("> 数据范围：视频 ").append(scope.get("videoId")).append("\n\n");

        JSONObject data = JSON.parseObject(insight.getResultJson());
        String summary = data.getString("summary");
        sb.append("## 摘要\n\n").append(summary == null ? "（无）" : summary).append("\n\n");

        appendList(sb, "用户需求", data.getJSONArray("needs"), "title", "description");
        appendList(sb, "主要痛点", data.getJSONArray("pain_points"), "title", "description");
        appendList(sb, "产品机会", data.getJSONArray("product_opportunities"), "title", "description");
        appendList(sb, "正面亮点", data.getJSONArray("highlights"), "title", "description");

        JSONObject purchase = data.getJSONObject("purchase_intent");
        if (purchase != null) {
            sb.append("## 购买意向\n\n");
            sb.append("- 整体判断：").append(purchase.getString("level")).append("\n");
            sb.append("- 说明：").append(purchase.getString("reason")).append("\n\n");
        }
        sb.append("---\n\n");
        sb.append("本报告的每条结论均可通过 `analysis_evidence` 追溯到原始评论。\n");
        return sb.toString();
    }

    private void appendList(StringBuilder sb, String heading,
                            com.alibaba.fastjson2.JSONArray arr, String titleKey, String descKey) {
        if (arr == null || arr.isEmpty()) {
            return;
        }
        sb.append("## ").append(heading).append("\n\n");
        for (int i = 0; i < arr.size(); i++) {
            JSONObject o = arr.getJSONObject(i);
            sb.append(i + 1).append(". **").append(o.getString(titleKey)).append("**");
            String desc = o.getString(descKey);
            if (desc != null && !desc.isEmpty()) {
                sb.append("：").append(desc);
            }
            com.alibaba.fastjson2.JSONArray ids = o.getJSONArray("evidence_comment_ids");
            if (ids != null && !ids.isEmpty()) {
                sb.append("（证据评论 ").append(ids.size()).append(" 条）");
            }
            sb.append("\n");
        }
        sb.append("\n");
    }
}
