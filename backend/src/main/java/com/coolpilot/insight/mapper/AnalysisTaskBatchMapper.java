package com.coolpilot.insight.mapper;

import com.coolpilot.insight.entity.AnalysisTaskBatch;

import org.apache.ibatis.annotations.Param;

import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AnalysisTaskBatchMapper extends BaseCrudMapper<AnalysisTaskBatch> {

    @Select("SELECT * FROM analysis_task_batch WHERE task_id = #{taskId} ORDER BY batch_no ASC")
    List<AnalysisTaskBatch> selectByTaskId(@Param("taskId") Long taskId);
}
