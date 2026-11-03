package com.coolskyai.coolpilot.insight.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 全局运营概览查询。
 *
 * <p>{@code videoIds} 为 null 表示管理员（无限制）；否则按可读视频集合过滤。
 */
public interface OverviewMapper {

    @Select("<script>"
            + "SELECT COUNT(*) AS videos, COALESCE(SUM(comment_count),0) AS collectedVideos "
            + "FROM video WHERE 1=1 "
            + "<if test='videoIds != null'> AND id IN "
            + "<foreach collection='videoIds' item='v' open='(' separator=',' close=')'>#{v}</foreach></if>"
            + "</script>")
    Map<String, Object> videoSummary(@Param("videoIds") List<Long> videoIds);

    @Select("<script>"
            + "SELECT COUNT(*) AS comments, COUNT(DISTINCT douyin_id) AS users, COALESCE(SUM(likes),0) AS likes "
            + "FROM comment WHERE 1=1 "
            + "<if test='videoIds != null'> AND video_id IN "
            + "<foreach collection='videoIds' item='v' open='(' separator=',' close=')'>#{v}</foreach></if>"
            + "</script>")
    Map<String, Object> commentSummary(@Param("videoIds") List<Long> videoIds);

    @Select("<script>"
            + "SELECT COUNT(*) AS analyzed, "
            + "SUM(CASE WHEN ca.review_status='PENDING' THEN 1 ELSE 0 END) AS pendingReview, "
            + "SUM(CASE WHEN ca.review_status='APPROVED' THEN 1 ELSE 0 END) AS approved, "
            + "SUM(CASE WHEN ca.review_status='CORRECTED' THEN 1 ELSE 0 END) AS corrected "
            + "FROM comment_analysis ca JOIN comment c ON c.id = ca.comment_id "
            + "WHERE 1=1 "
            + "<if test='videoIds != null'> AND c.video_id IN "
            + "<foreach collection='videoIds' item='v' open='(' separator=',' close=')'>#{v}</foreach></if>"
            + "</script>")
    Map<String, Object> analysisSummary(@Param("videoIds") List<Long> videoIds);

    @Select("<script>"
            + "SELECT DATE_FORMAT(c.publish_time,'%Y-%m-%d') AS date, COUNT(*) AS count "
            + "FROM comment c WHERE 1=1 "
            + "<if test='videoIds != null'> AND c.video_id IN "
            + "<foreach collection='videoIds' item='v' open='(' separator=',' close=')'>#{v}</foreach></if>"
            + " GROUP BY DATE_FORMAT(c.publish_time,'%Y-%m-%d') ORDER BY date DESC LIMIT 14"
            + "</script>")
    List<Map<String, Object>> dailyTrend(@Param("videoIds") List<Long> videoIds);

    @Select("<script>"
            + "SELECT v.aweme_id AS videoId, v.title, COUNT(c.id) AS comments "
            + "FROM video v LEFT JOIN comment c ON c.video_id = v.id WHERE 1=1 "
            + "<if test='videoIds != null'> AND v.id IN "
            + "<foreach collection='videoIds' item='v' open='(' separator=',' close=')'>#{v}</foreach></if>"
            + " GROUP BY v.id, v.aweme_id, v.title ORDER BY comments DESC LIMIT 10"
            + "</script>")
    List<Map<String, Object>> topVideos(@Param("videoIds") List<Long> videoIds);

    /** 全库高频痛点（人工纠正后的数据优先） */
    @Select("<script>"
            + "SELECT ca.pain_points_json AS painPointsJson FROM comment_analysis ca "
            + "JOIN comment c ON c.id = ca.comment_id "
            + "JOIN (SELECT comment_id, MAX(id) AS mid FROM comment_analysis GROUP BY comment_id) t "
            + "  ON t.mid = ca.id "
            + "WHERE ca.pain_points_json IS NOT NULL AND ca.review_status != 'REJECTED' "
            + "<if test='videoIds != null'> AND c.video_id IN "
            + "<foreach collection='videoIds' item='v' open='(' separator=',' close=')'>#{v}</foreach></if>"
            + " LIMIT 5000"
            + "</script>")
    List<String> painPointJsonList(@Param("videoIds") List<Long> videoIds);
}
