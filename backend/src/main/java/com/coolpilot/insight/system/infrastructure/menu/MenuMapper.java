package com.coolpilot.insight.system.infrastructure.menu;

import com.coolpilot.insight.system.domain.menu.Menu;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface MenuMapper {

    @Select("SELECT * FROM sys_menu WHERE visible = 1 ORDER BY sort, id")
    List<Menu> selectVisible();

    @Select("SELECT * FROM sys_menu ORDER BY sort, id")
    List<Menu> selectAll();

    @Select("SELECT * FROM sys_menu WHERE id = #{id}")
    Menu selectById(@Param("id") Long id);

    @Insert("INSERT INTO sys_menu (parent_id, name, path, icon, menu_type, permission_code, sort, visible, status) "
            + "VALUES (#{parentId}, #{name}, #{path}, #{icon}, #{menuType}, #{permissionCode}, #{sort}, 1, #{status})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Menu menu);

    @Update("UPDATE sys_menu SET name = #{name}, path = #{path}, icon = #{icon}, sort = #{sort}, "
            + "status = #{status}, permission_code = #{permissionCode}, updated_at = NOW() WHERE id = #{id}")
    int update(Menu menu);

    @Update("UPDATE sys_menu SET status = #{status}, updated_at = NOW() WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    @Delete("DELETE FROM sys_menu WHERE id = #{id}")
    int deleteById(@Param("id") Long id);

    @Select("SELECT COUNT(*) FROM sys_menu WHERE parent_id = #{id}")
    int countChildren(@Param("id") Long id);
}
