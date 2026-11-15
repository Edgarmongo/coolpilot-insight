package com.coolpilot.insight.mapper;

import com.coolpilot.insight.entity.AnalysisTaskItem;

import org.apache.ibatis.annotations.Param;

import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AnalysisTaskItemMapper extends BaseCrudMapper<AnalysisTaskItem> {

    @Select("SELECT * FROM analysis_task_item WHERE task_id = #{taskId} "
            + "AND status = 'PENDING' ORDER BY id ASC")
    List<AnalysisTaskItem> selectPendingByTask(@Param("taskId") Long taskId);
}
