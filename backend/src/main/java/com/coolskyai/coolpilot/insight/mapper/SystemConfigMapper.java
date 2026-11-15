package com.coolskyai.coolpilot.insight.mapper;

import com.coolskyai.coolpilot.insight.entity.SystemConfig;

import java.util.List;

import org.apache.ibatis.annotations.Select;

public interface SystemConfigMapper extends BaseCrudMapper<SystemConfig> {

    @Select("SELECT * FROM system_config")
    List<SystemConfig> selectAllConfig();
}
