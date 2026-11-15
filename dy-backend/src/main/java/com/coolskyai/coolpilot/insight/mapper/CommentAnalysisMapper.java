package com.coolskyai.coolpilot.insight.mapper;

import com.coolskyai.coolpilot.insight.entity.CommentAnalysis;

import org.apache.ibatis.annotations.Param;

import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface CommentAnalysisMapper extends BaseCrudMapper<CommentAnalysis> {

    @Select("SELECT comment_id FROM comment_analysis WHERE profile_id = #{profileId}")
    List<Long> selectCommentIdsByProfile(@Param("profileId") Long profileId);

    @Select("SELECT * FROM comment_analysis WHERE comment_id = #{commentId} "
            + "AND profile_id = #{profileId} LIMIT 1")
    CommentAnalysis selectByCommentAndProfile(@Param("commentId") Long commentId,
                                              @Param("profileId") Long profileId);

    @Select("<script>SELECT * FROM comment_analysis WHERE comment_id IN "
            + "<foreach collection='commentIds' item='c' open='(' separator=',' close=')'>#{c}</foreach>"
            + " ORDER BY id DESC</script>")
    List<CommentAnalysis> selectByCommentIdsDesc(@Param("commentIds") List<Long> commentIds);
}
