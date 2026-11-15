package com.coolpilot.insight.system.infrastructure.persistence;

import com.coolpilot.insight.mapper.BaseCrudMapper;

import com.coolpilot.insight.system.domain.SystemConfig;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SystemConfigMapper extends BaseCrudMapper<SystemConfig> {

    @Select("SELECT * FROM system_config")
    List<SystemConfig> selectAllConfig();

    @Select("SELECT * FROM system_config WHERE config_key = #{configKey} LIMIT 1")
    SystemConfig selectByKey(@Param("configKey") String configKey);

    @org.apache.ibatis.annotations.Update("UPDATE system_config SET config_value = #{configValue}, "
            + "updated_by = #{updatedBy}, updated_at = NOW() WHERE config_key = #{configKey}")
    int updateValue(@Param("configKey") String configKey,
                    @Param("configValue") String configValue,
                    @Param("updatedBy") Long updatedBy);
}
