package com.coolskyai.coolpilot.insight.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.coolskyai.coolpilot.insight.entity.Comment;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

public interface CommentMapper extends BaseMapper<Comment> {

    /**
     * 评论列表（附带最新一条情感标签）。情感已迁至 comment_analysis，
     * 列表仍需展示，故用 LEFT JOIN 取最新记录。
     */
    @Select("<script>"
            + "SELECT c.id, c.cid, c.nickname, c.douyin_id AS douyinId, c.douyin_uid AS douyinUid, "
            + "c.ip_region AS region, c.content, c.likes, c.publish_time AS publishTime, "
            + "c.is_reply AS isReply, c.reply_count AS replyCount, c.parent_cid AS parentCid, "
            + "ca.sentiment AS sentimentText, "
            + "CASE ca.sentiment WHEN 'positive' THEN 1 WHEN 'negative' THEN -1 WHEN 'neutral' THEN 0 ELSE NULL END AS sentiment "
            + "FROM comment c "
            + "LEFT JOIN comment_analysis ca ON ca.id = ("
            + "  SELECT ca2.id FROM comment_analysis ca2 WHERE ca2.comment_id = c.id ORDER BY ca2.id DESC LIMIT 1) "
            + "WHERE c.video_id = #{videoId} "
            + "<if test='region != null and region != \"\"'> AND c.ip_region = #{region} </if>"
            + "<if test='sentimentText != null'> AND ca.sentiment = #{sentimentText} </if>"
            + "ORDER BY ${orderBy} LIMIT #{offset}, #{size}"
            + "</script>")
    List<Map<String, Object>> selectCommentViews(@Param("videoId") Long videoId,
                                                 @Param("region") String region,
                                                 @Param("sentimentText") String sentimentText,
                                                 @Param("orderBy") String orderBy,
                                                 @Param("offset") int offset,
                                                 @Param("size") int size);

    @Select("<script>"
            + "SELECT COUNT(*) FROM comment c "
            + "LEFT JOIN comment_analysis ca ON ca.id = ("
            + "  SELECT ca2.id FROM comment_analysis ca2 WHERE ca2.comment_id = c.id ORDER BY ca2.id DESC LIMIT 1) "
            + "WHERE c.video_id = #{videoId} "
            + "<if test='region != null and region != \"\"'> AND c.ip_region = #{region} </if>"
            + "<if test='sentimentText != null'> AND ca.sentiment = #{sentimentText} </if>"
            + "</script>")
    long countCommentViews(@Param("videoId") Long videoId,
                           @Param("region") String region,
                           @Param("sentimentText") String sentimentText);

    @Select("<script>"
            + "SELECT c.id, c.cid, c.nickname, c.douyin_id AS douyinId, c.ip_region AS region, "
            + "c.content, c.likes, c.publish_time AS publishTime, c.reply_count AS replyCount, c.is_reply AS isReply "
            + "FROM comment c WHERE 1=1 "
            + "<if test='videoId != null'> AND c.video_id = #{videoId} </if>"
            + " AND c.content LIKE CONCAT('%', #{keyword}, '%') "
            + "ORDER BY c.publish_time DESC LIMIT #{offset}, #{size}"
            + "</script>")
    List<Map<String, Object>> searchCommentViews(@Param("keyword") String keyword,
                                                 @Param("videoId") Long videoId,
                                                 @Param("offset") int offset,
                                                 @Param("size") int size);

    @Select("<script>"
            + "SELECT COUNT(*) FROM comment c WHERE 1=1 "
            + "<if test='videoId != null'> AND c.video_id = #{videoId} </if>"
            + " AND c.content LIKE CONCAT('%', #{keyword}, '%')"
            + "</script>")
    long countSearchViews(@Param("keyword") String keyword, @Param("videoId") Long videoId);

    @Select("SELECT ip_region AS region, COUNT(*) AS count FROM comment WHERE video_id = #{videoId} "
            + "GROUP BY ip_region ORDER BY count DESC")
    List<Map<String, Object>> regionStats(@Param("videoId") Long videoId);

    @Select("SELECT DATE_FORMAT(publish_time, '%Y-%m-%d') AS date, COUNT(*) AS count FROM comment "
            + "WHERE video_id = #{videoId} GROUP BY DATE_FORMAT(publish_time, '%Y-%m-%d') ORDER BY date")
    List<Map<String, Object>> dailyStats(@Param("videoId") Long videoId);

    @Select("SELECT c.id, c.nickname, c.douyin_id AS douyinId, c.ip_region AS region, c.content, c.likes, "
            + "c.publish_time AS publishTime FROM comment c WHERE c.video_id = #{videoId} "
            + "ORDER BY c.likes DESC, c.id ASC LIMIT #{limit}")
    List<Map<String, Object>> topComments(@Param("videoId") Long videoId, @Param("limit") int limit);

    @Select("SELECT id, content, likes, nickname, ip_region AS region, publish_time AS publishTime, "
            + "cid, douyin_id AS douyinId FROM comment WHERE video_id = #{videoId} "
            + "ORDER BY likes DESC, id ASC LIMIT #{limit}")
    List<Map<String, Object>> commentsForAnalysis(@Param("videoId") Long videoId, @Param("limit") int limit);

    /** 评论总数 / 用户数 / 总赞数，一次查询完成，避免三次全表扫描 */
    @Select("SELECT COUNT(*) AS total, COUNT(DISTINCT douyin_id) AS users, COALESCE(SUM(likes),0) AS likes "
            + "FROM comment WHERE video_id = #{videoId}")
    Map<String, Object> summaryRow(@Param("videoId") Long videoId);

    /** 评论树原始行：一次取够，Java 侧组树（避免 N+1 查询） */
    @Select("SELECT c.id, c.cid, c.parent_cid AS parentCid, c.root_cid AS rootCid, "
            + "c.nickname, c.douyin_id AS douyinId, c.ip_region AS region, c.content, c.likes, "
            + "c.publish_time AS publishTime, c.is_reply AS isReply, c.reply_count AS replyCount "
            + "FROM comment c WHERE c.video_id = #{videoId} "
            + "ORDER BY c.likes DESC, c.id ASC LIMIT #{limit}")
    List<Map<String, Object>> treeRows(@Param("videoId") Long videoId, @Param("limit") int limit);

    /** 评论 + 最新情感标签（供统计/图表/AI 编排使用） */
    @Select("SELECT c.id, c.content, c.likes, c.nickname, c.ip_region AS region, "
            + "c.publish_time AS publishTime, c.reply_count AS replyCount, c.is_reply AS isReply, "
            + "c.cid, c.douyin_id AS douyinId, ca.sentiment AS sentimentText "
            + "FROM comment c "
            + "LEFT JOIN comment_analysis ca ON ca.id = ("
            + "  SELECT ca2.id FROM comment_analysis ca2 WHERE ca2.comment_id = c.id ORDER BY ca2.id DESC LIMIT 1) "
            + "WHERE c.video_id = #{videoId} ORDER BY c.publish_time ASC, c.id ASC")
    List<Map<String, Object>> commentRowsWithSentiment(@Param("videoId") Long videoId);

    /** 按情感聚合（从 comment_analysis 取最新一条） */
    @Select("SELECT ca.sentiment AS sentiment, COUNT(*) AS count FROM comment c "
            + "JOIN comment_analysis ca ON ca.id = ("
            + "  SELECT ca2.id FROM comment_analysis ca2 WHERE ca2.comment_id = c.id ORDER BY ca2.id DESC LIMIT 1) "
            + "WHERE c.video_id = #{videoId} AND ca.sentiment IS NOT NULL "
            + "GROUP BY ca.sentiment")
    List<Map<String, Object>> sentimentStats(@Param("videoId") Long videoId);
}
