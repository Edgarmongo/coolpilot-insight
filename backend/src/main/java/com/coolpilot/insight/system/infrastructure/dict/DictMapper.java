package com.coolpilot.insight.system.infrastructure.dict;

import com.coolpilot.insight.system.domain.dict.DictItem;
import com.coolpilot.insight.system.domain.dict.DictType;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface DictMapper {

    @Select("SELECT * FROM sys_dict_type ORDER BY id")
    List<DictType> selectTypes();

    @Select("SELECT * FROM sys_dict_type WHERE type_code = #{code}")
    DictType selectTypeByCode(@Param("code") String code);

    @Select("SELECT * FROM sys_dict_type WHERE id = #{id}")
    DictType selectTypeById(@Param("id") Long id);

    @Insert("INSERT INTO sys_dict_type (type_code, type_name, remark) VALUES (#{typeCode}, #{typeName}, #{remark})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertType(DictType type);

    @Delete("DELETE FROM sys_dict_type WHERE id = #{id}")
    int deleteType(@Param("id") Long id);

    @Select("SELECT * FROM sys_dict_item WHERE type_id = #{typeId} ORDER BY sort, id")
    List<DictItem> selectItems(@Param("typeId") Long typeId);

    @Select("SELECT * FROM sys_dict_item WHERE type_id = #{typeId} AND item_value = #{value}")
    DictItem selectItem(@Param("typeId") Long typeId, @Param("value") String value);

    @Insert("INSERT INTO sys_dict_item (type_id, item_value, item_label, sort, status) "
            + "VALUES (#{typeId}, #{itemValue}, #{itemLabel}, #{sort}, 'ENABLED')")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertItem(DictItem item);

    @Update("UPDATE sys_dict_item SET item_label = #{itemLabel}, sort = #{sort}, updated_at = NOW() WHERE id = #{id}")
    int updateItem(DictItem item);

    @Update("UPDATE sys_dict_item SET status = #{status}, updated_at = NOW() WHERE id = #{id}")
    int updateItemStatus(@Param("id") Long id, @Param("status") String status);

    @Delete("DELETE FROM sys_dict_item WHERE id = #{id}")
    int deleteItem(@Param("id") Long id);
}
