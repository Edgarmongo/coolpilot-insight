package com.coolpilot.insight.mapper;

import com.coolpilot.insight.entity.KeywordVideo;

import org.apache.ibatis.annotations.Param;

import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface KeywordVideoMapper extends BaseCrudMapper<KeywordVideo> {

    @Select("SELECT COUNT(*) FROM keyword_video WHERE keyword_id = #{keywordId}")
    long countByKeywordId(@Param("keywordId") Long keywordId);

    @Select("SELECT COUNT(*) FROM keyword_video WHERE keyword_id = #{keywordId} AND video_id = #{videoId}")
    long countByKeywordAndVideo(@Param("keywordId") Long keywordId, @Param("videoId") Long videoId);

    @Select("SELECT * FROM keyword_video WHERE keyword_id = #{keywordId} ORDER BY id DESC LIMIT #{limit}")
    List<KeywordVideo> selectByKeyword(@Param("keywordId") Long keywordId, @Param("limit") int limit);
}
