package com.coolskyai.coolpilot.insight.system.infrastructure.persistence;

import com.coolskyai.coolpilot.insight.mapper.BaseCrudMapper;

import com.coolskyai.coolpilot.insight.system.domain.SystemConfig;

import java.util.List;

import org.apache.ibatis.annotations.Select;

public interface SystemConfigMapper extends BaseCrudMapper<SystemConfig> {

    @Select("SELECT * FROM system_config")
    List<SystemConfig> selectAllConfig();
}
