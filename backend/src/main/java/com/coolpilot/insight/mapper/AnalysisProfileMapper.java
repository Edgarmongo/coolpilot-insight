package com.coolpilot.insight.mapper;

import com.coolpilot.insight.entity.AnalysisProfile;

import org.apache.ibatis.annotations.Param;

import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AnalysisProfileMapper extends BaseCrudMapper<AnalysisProfile> {

    @Select("SELECT * FROM analysis_profile WHERE profile_key = #{profileKey} LIMIT 1")
    AnalysisProfile selectByProfileKey(@Param("profileKey") String profileKey);
}
