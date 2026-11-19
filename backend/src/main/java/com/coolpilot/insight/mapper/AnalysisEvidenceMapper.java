package com.coolpilot.insight.mapper;

import com.coolpilot.insight.entity.AnalysisEvidence;

import org.apache.ibatis.annotations.Param;

import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AnalysisEvidenceMapper extends BaseCrudMapper<AnalysisEvidence> {

    @org.apache.ibatis.annotations.Delete("DELETE FROM analysis_evidence "
            + "WHERE comment_analysis_id = #{commentAnalysisId} AND source_level = #{sourceLevel}")
    int deleteByCommentAnalysisAndSource(@Param("commentAnalysisId") Long commentAnalysisId,
                                         @Param("sourceLevel") String sourceLevel);
}
