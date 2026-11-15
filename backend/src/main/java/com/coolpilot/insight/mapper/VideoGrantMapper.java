package com.coolpilot.insight.mapper;

import com.coolpilot.insight.entity.VideoGrant;

import org.apache.ibatis.annotations.Param;

import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface VideoGrantMapper extends BaseCrudMapper<VideoGrant> {

    @Select("SELECT COUNT(*) FROM video_grant WHERE grantee_user_id = #{userId} "
            + "AND video_id = #{videoId} AND (expires_at IS NULL OR expires_at > NOW())")
    long countActiveGrant(@Param("userId") Long userId, @Param("videoId") Long videoId);

    @Select("SELECT * FROM video_grant WHERE grantee_user_id = #{granteeUserId}")
    List<VideoGrant> selectByGrantee(@Param("granteeUserId") Long granteeUserId);
}
