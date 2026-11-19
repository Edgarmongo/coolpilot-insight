package com.coolpilot.insight.collection.infrastructure.persistence;

import com.coolpilot.insight.mapper.BaseCrudMapper;

import com.coolpilot.insight.collection.domain.CollectionTaskLog;

import org.apache.ibatis.annotations.Param;

import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface CollectionTaskLogMapper extends BaseCrudMapper<CollectionTaskLog> {

    @Select("SELECT * FROM collection_task_log WHERE task_id = #{taskId} ORDER BY id ASC LIMIT #{limit}")
    List<CollectionTaskLog> selectByTaskLimit(@Param("taskId") Long taskId, @Param("limit") int limit);
}
