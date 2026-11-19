package com.coolpilot.insight.mapper;

import com.coolpilot.insight.entity.AnalysisReview;

import org.apache.ibatis.annotations.Param;

import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AnalysisReviewMapper extends BaseCrudMapper<AnalysisReview> {

    @Select("SELECT * FROM analysis_review WHERE comment_analysis_id = #{analysisId} ORDER BY id DESC LIMIT 50")
    List<AnalysisReview> selectByCommentAnalysisId(@Param("analysisId") Long analysisId);
}
