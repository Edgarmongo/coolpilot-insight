package com.coolpilot.insight.mapper;

import com.coolpilot.insight.entity.VideoInsight;

import org.apache.ibatis.annotations.Param;

import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface VideoInsightMapper extends BaseCrudMapper<VideoInsight> {

    @Select("SELECT * FROM video_insight WHERE video_id = #{videoId} ORDER BY id DESC LIMIT 1")
    VideoInsight selectLatestByVideoId(@Param("videoId") Long videoId);

    @Select("SELECT * FROM video_insight WHERE video_id = #{videoId} "
            + "ORDER BY id DESC LIMIT #{limit}")
    List<VideoInsight> selectByVideoIdLimit(@Param("videoId") Long videoId, @Param("limit") int limit);
}
