package com.coolskyai.coolpilot.insight.mapper;

import com.coolskyai.coolpilot.insight.entity.Video;

import org.apache.ibatis.annotations.Param;

import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface VideoMapper extends BaseCrudMapper<Video> {

    @Select("SELECT * FROM video WHERE aweme_id = #{awemeId} LIMIT 1")
    Video selectByAwemeId(@Param("awemeId") String awemeId);

    @Select("<script>SELECT * FROM video"
            + "<where><if test='ids != null'> id IN "
            + "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>"
            + "</if></where> ORDER BY id DESC LIMIT #{limit}</script>")
    List<Video> selectOrdered(@Param("ids") List<Long> ids, @Param("limit") int limit);
}
