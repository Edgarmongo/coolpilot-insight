package com.coolskyai.coolpilot.insight.mapper;

import com.coolskyai.coolpilot.insight.entity.CollectionCheckpoint;

import org.apache.ibatis.annotations.Param;

import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface CollectionCheckpointMapper extends BaseCrudMapper<CollectionCheckpoint> {

    @Select("SELECT * FROM collection_checkpoint WHERE task_item_id = #{taskItemId} LIMIT 1")
    CollectionCheckpoint selectByTaskItemId(@Param("taskItemId") Long taskItemId);
}
