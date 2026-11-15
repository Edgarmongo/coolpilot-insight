package com.coolskyai.coolpilot.insight.mapper;

import com.coolskyai.coolpilot.insight.entity.Report;

import org.apache.ibatis.annotations.Param;

import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ReportMapper extends BaseCrudMapper<Report> {

    @Select("<script>SELECT * FROM report WHERE deleted_at IS NULL"
            + "<if test='ownerUserId != null'> AND owner_user_id = #{ownerUserId}</if>"
            + " ORDER BY id DESC LIMIT 100</script>")
    List<Report> selectActive(@Param("ownerUserId") Long ownerUserId);
}
