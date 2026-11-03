package com.coolskyai.coolpilot.insight.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.coolskyai.coolpilot.insight.entity.CommentAnalysis;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

public interface AnalysisReviewMapperExt extends BaseMapper<CommentAnalysis> {

    /**
     * 复核队列：评论原文 + AI 结构化结果。只取每条评论最新一次分析。
     */
    @Select("<script>"
            + "SELECT ca.id AS analysisId, ca.comment_id AS commentId, ca.sentiment, ca.intent, "
            + "ca.topics_json AS topicsJson, ca.pain_points_json AS painPointsJson, ca.user_need AS userNeed, "
            + "ca.purchase_intent AS purchaseIntent, ca.confidence, ca.is_uncertain AS isUncertain, "
            + "ca.review_status AS reviewStatus, ca.reviewed_at AS reviewedAt, "
            + "c.content, c.nickname, c.ip_region AS region, c.likes, c.publish_time AS publishTime "
            + "FROM comment_analysis ca "
            + "JOIN comment c ON c.id = ca.comment_id "
            + "JOIN (SELECT comment_id, MAX(id) AS mid FROM comment_analysis GROUP BY comment_id) t "
            + "  ON t.mid = ca.id "
            + "WHERE c.video_id = #{videoId} "
            + "<if test='status != null and status != \"\"'> AND ca.review_status = #{status} </if>"
            + "<if test='lowConfidence != null and lowConfidence'> AND ca.is_uncertain = 1 </if>"
            + "ORDER BY ca.is_uncertain DESC, ca.confidence ASC, ca.id ASC "
            + "LIMIT #{offset}, #{size}"
            + "</script>")
    List<Map<String, Object>> selectQueue(@Param("videoId") Long videoId,
                                          @Param("status") String status,
                                          @Param("lowConfidence") Boolean lowConfidence,
                                          @Param("offset") int offset,
                                          @Param("size") int size);

    @Select("<script>"
            + "SELECT COUNT(*) FROM comment_analysis ca "
            + "JOIN comment c ON c.id = ca.comment_id "
            + "JOIN (SELECT comment_id, MAX(id) AS mid FROM comment_analysis GROUP BY comment_id) t "
            + "  ON t.mid = ca.id "
            + "WHERE c.video_id = #{videoId} "
            + "<if test='status != null and status != \"\"'> AND ca.review_status = #{status} </if>"
            + "<if test='lowConfidence != null and lowConfidence'> AND ca.is_uncertain = 1 </if>"
            + "</script>")
    long countQueue(@Param("videoId") Long videoId,
                    @Param("status") String status,
                    @Param("lowConfidence") Boolean lowConfidence);

    /** 按复核状态统计（只算每条评论最新一次分析） */
    @Select("SELECT ca.review_status AS status, COUNT(*) AS count FROM comment_analysis ca "
            + "JOIN comment c ON c.id = ca.comment_id "
            + "JOIN (SELECT comment_id, MAX(id) AS mid FROM comment_analysis GROUP BY comment_id) t "
            + "  ON t.mid = ca.id "
            + "WHERE c.video_id = #{videoId} GROUP BY ca.review_status")
    List<Map<String, Object>> statsByVideo(@Param("videoId") Long videoId);
}
