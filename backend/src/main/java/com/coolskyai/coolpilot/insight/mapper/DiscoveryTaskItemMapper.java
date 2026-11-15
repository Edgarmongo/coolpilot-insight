package com.coolskyai.coolpilot.insight.mapper;

import com.coolskyai.coolpilot.insight.entity.DiscoveryTaskItem;

import org.apache.ibatis.annotations.Param;

import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface DiscoveryTaskItemMapper extends BaseCrudMapper<DiscoveryTaskItem> {

    @Select("SELECT COUNT(*) FROM discovery_task_item WHERE task_id = #{taskId} AND video_id = #{videoId}")
    long countByTaskAndVideo(@Param("taskId") Long taskId, @Param("videoId") Long videoId);
}
