package com.coolpilot.insight.system.infrastructure.role;

import com.coolpilot.insight.system.domain.role.Role;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface RoleMapper {

    @Select("SELECT * FROM sys_role ORDER BY id")
    List<Role> selectAll();

    @Select("SELECT * FROM sys_role WHERE id = #{id}")
    Role selectById(@Param("id") Long id);

    @Select("SELECT * FROM sys_role WHERE code = #{code}")
    Role selectByCode(@Param("code") String code);

    @Insert("INSERT INTO sys_role (code, name, builtin, status) VALUES (#{code}, #{name}, 0, 'ENABLED')")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Role role);

    @Update("UPDATE sys_role SET status = #{status}, updated_at = NOW() WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    @Delete("DELETE FROM sys_role WHERE id = #{id} AND builtin = 0")
    int deleteNonBuiltin(@Param("id") Long id);

    @Select("SELECT COUNT(*) FROM sys_user_role WHERE role_id = #{roleId}")
    int countUsers(@Param("roleId") Long roleId);

    @Select("SELECT permission_id FROM sys_role_permission WHERE role_id = #{roleId}")
    List<Long> selectPermissionIds(@Param("roleId") Long roleId);

    @Select("SELECT r.* FROM sys_role r JOIN sys_user_role ur ON ur.role_id = r.id WHERE ur.user_id = #{userId}")
    List<Role> selectByUserId(@Param("userId") Long userId);

    @Insert("INSERT IGNORE INTO sys_user_role (user_id, role_id) VALUES (#{userId}, #{roleId})")
    int assign(@Param("userId") Long userId, @Param("roleId") Long roleId);

    @Delete("DELETE FROM sys_user_role WHERE user_id = #{userId} AND role_id = #{roleId}")
    int revoke(@Param("userId") Long userId, @Param("roleId") Long roleId);
}
