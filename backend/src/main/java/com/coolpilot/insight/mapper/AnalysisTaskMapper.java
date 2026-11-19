package com.coolpilot.insight.mapper;

import com.coolpilot.insight.entity.AnalysisTask;

import org.apache.ibatis.annotations.Param;

import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AnalysisTaskMapper extends BaseCrudMapper<AnalysisTask> {

    @Select("<script>SELECT * FROM analysis_task"
            + "<if test='ownerUserId != null'> WHERE owner_user_id = #{ownerUserId}</if>"
            + " ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<AnalysisTask> selectOrdered(@Param("ownerUserId") Long ownerUserId,
                                     @Param("limit") int limit, @Param("offset") int offset);
}
