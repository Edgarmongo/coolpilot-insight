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

    /*
     * 任务级汇总：输入/输出/成本必须分别求和。
     * 历史实现用 tokensByTask()/2 对半分，导致 input==output 且 cost 恒为 0。
     */
    @Select("SELECT COALESCE(SUM(input_tokens), 0) FROM usage_record "
            + "WHERE biz_ref_id = #{taskId} AND status = 'OK'")
    long inputTokensByTask(@Param("taskId") Long taskId);

    @Select("SELECT COALESCE(SUM(output_tokens), 0) FROM usage_record "
            + "WHERE biz_ref_id = #{taskId} AND status = 'OK'")
    long outputTokensByTask(@Param("taskId") Long taskId);

    @Select("SELECT COALESCE(SUM(cost_amount), 0) FROM usage_record "
            + "WHERE biz_ref_id = #{taskId} AND status = 'OK'")
    java.math.BigDecimal costByTask(@Param("taskId") Long taskId);
}
