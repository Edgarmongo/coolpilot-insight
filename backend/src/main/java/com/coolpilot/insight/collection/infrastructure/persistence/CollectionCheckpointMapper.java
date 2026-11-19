package com.coolpilot.insight.collection.infrastructure.persistence;

import com.coolpilot.insight.mapper.BaseCrudMapper;

import com.coolpilot.insight.collection.domain.CollectionCheckpoint;

import org.apache.ibatis.annotations.Param;

import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface CollectionCheckpointMapper extends BaseCrudMapper<CollectionCheckpoint> {

    @Select("SELECT * FROM collection_checkpoint WHERE task_item_id = #{taskItemId} LIMIT 1")
    CollectionCheckpoint selectByTaskItemId(@Param("taskItemId") Long taskItemId);
}
