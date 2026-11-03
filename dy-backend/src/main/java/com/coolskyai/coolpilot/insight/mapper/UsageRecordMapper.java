package com.coolskyai.coolpilot.insight.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.coolskyai.coolpilot.insight.entity.UsageRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface UsageRecordMapper extends BaseMapper<UsageRecord> {

    /** 当日已消耗 token（用于预算护栏） */
    @Select("SELECT COALESCE(SUM(input_tokens + output_tokens), 0) FROM usage_record "
            + "WHERE DATE(created_at) = CURDATE()")
    long todayTokens();

    @Select("SELECT COALESCE(SUM(cost_amount), 0) FROM usage_record WHERE DATE(created_at) = CURDATE()")
    java.math.BigDecimal todayCost();

    @Select("SELECT COALESCE(SUM(input_tokens + output_tokens), 0) FROM usage_record "
            + "WHERE biz_ref_id = #{taskId}")
    long tokensByTask(@Param("taskId") Long taskId);
}
