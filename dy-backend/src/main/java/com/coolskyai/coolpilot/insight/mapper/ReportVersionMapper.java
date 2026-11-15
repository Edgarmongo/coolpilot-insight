package com.coolskyai.coolpilot.insight.mapper;

import com.coolskyai.coolpilot.insight.entity.ReportVersion;

import org.apache.ibatis.annotations.Param;

import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ReportVersionMapper extends BaseCrudMapper<ReportVersion> {

    @Select("SELECT * FROM report_version WHERE report_id = #{reportId} "
            + "ORDER BY version_no DESC LIMIT 1")
    ReportVersion selectLatestByReportId(@Param("reportId") Long reportId);

    @Select("SELECT * FROM report_version WHERE report_id = #{reportId} ORDER BY version_no DESC")
    List<ReportVersion> selectByReportId(@Param("reportId") Long reportId);
}
