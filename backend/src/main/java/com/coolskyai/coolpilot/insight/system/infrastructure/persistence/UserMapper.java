package com.coolskyai.coolpilot.insight.system.infrastructure.persistence;

import com.coolskyai.coolpilot.insight.mapper.BaseCrudMapper;

import com.coolskyai.coolpilot.insight.system.domain.User;

import org.apache.ibatis.annotations.Param;

import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface UserMapper extends BaseCrudMapper<User> {

    @Select("SELECT COUNT(*) FROM user WHERE phone = #{phone}")
    long countByPhone(@Param("phone") String phone);

    @Select("SELECT * FROM user WHERE phone = #{phone} AND deleted_at IS NULL LIMIT 1")
    User selectByPhone(@Param("phone") String phone);

    @Select("SELECT * FROM user ORDER BY id ASC LIMIT #{limit} OFFSET #{offset}")
    List<User> selectPageOrderById(@Param("offset") int offset, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM user")
    long countAll();
}
