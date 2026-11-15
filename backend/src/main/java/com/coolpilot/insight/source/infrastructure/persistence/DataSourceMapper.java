package com.coolpilot.insight.source.infrastructure.persistence;

import com.coolpilot.insight.source.domain.DataSource;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface DataSourceMapper {

    @Select("SELECT * FROM ds_source ORDER BY id")
    List<DataSource> selectAll();

    @Select("SELECT * FROM ds_source WHERE code = #{code} LIMIT 1")
    DataSource selectByCode(@Param("code") String code);

    @Select("SELECT * FROM ds_source WHERE id = #{id}")
    DataSource selectById(@Param("id") Long id);

    @org.apache.ibatis.annotations.Update("UPDATE ds_source SET status = #{status}, updated_at = NOW() WHERE code = #{code}")
    int updateStatus(@Param("code") String code, @Param("status") String status);
}
