package com.coolpilot.insight.system.infrastructure.persistence;

import com.coolpilot.insight.mapper.BaseCrudMapper;
import com.coolpilot.insight.system.domain.AuditLog;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AuditLogMapper extends BaseCrudMapper<AuditLog> {

    @Select("<script>SELECT * FROM audit_log"
            + "<where><if test='action != null and action.length() > 0'> AND action = #{action}</if>"
            + "<if test='userId != null'> AND user_id = #{userId}</if></where>"
            + " ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<AuditLog> selectPage(@Param("action") String action,
                              @Param("userId") Long userId,
                              @Param("limit") int limit,
                              @Param("offset") int offset);

    @Select("<script>SELECT COUNT(*) FROM audit_log"
            + "<where><if test='action != null and action.length() > 0'> AND action = #{action}</if>"
            + "<if test='userId != null'> AND user_id = #{userId}</if></where></script>")
    long countFiltered(@Param("action") String action, @Param("userId") Long userId);
}
