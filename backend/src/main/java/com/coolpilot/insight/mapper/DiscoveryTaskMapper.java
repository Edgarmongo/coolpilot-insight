package com.coolpilot.insight.mapper;

import com.coolpilot.insight.entity.DiscoveryTask;

import org.apache.ibatis.annotations.Param;

import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface DiscoveryTaskMapper extends BaseCrudMapper<DiscoveryTask> {

    @Select("<script>SELECT * FROM discovery_task"
            + "<if test='ownerUserId != null'> WHERE owner_user_id = #{ownerUserId}</if>"
            + " ORDER BY id DESC LIMIT #{limit}</script>")
    List<DiscoveryTask> selectOrdered(@Param("ownerUserId") Long ownerUserId, @Param("limit") int limit);
}
