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

    /**
     * 覆盖写能力声明（SOURCE-04-06）。
     *
     * <p>**只写 {@code capability_json} 一列**：不碰 {@code status}，也不碰
     * {@code ds_account.auth_status}（后者由账号验证独占）。
     * 语句里写死列名，是为了让"改了哪些字段"在代码级可审——
     * 用整实体 update 会把两个域混在一起。
     */
    @org.apache.ibatis.annotations.Update(
            "UPDATE ds_source SET capability_json = #{json}, updated_at = NOW() WHERE code = #{code}")
    int updateCapabilityJson(@Param("code") String code, @Param("json") String json);
}
