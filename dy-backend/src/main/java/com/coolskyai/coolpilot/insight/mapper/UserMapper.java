package com.coolskyai.coolpilot.insight.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.coolskyai.coolpilot.insight.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
