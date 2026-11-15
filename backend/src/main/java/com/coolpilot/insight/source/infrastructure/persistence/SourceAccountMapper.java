package com.coolpilot.insight.source.infrastructure.persistence;

import com.coolpilot.insight.source.domain.SourceAccount;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface SourceAccountMapper {

    @Select("SELECT * FROM ds_account WHERE source_id = #{sourceId} ORDER BY id DESC")
    List<SourceAccount> selectBySource(@Param("sourceId") Long sourceId);

    @Select("SELECT * FROM ds_account WHERE id = #{id}")
    SourceAccount selectById(@Param("id") Long id);

    @Insert("INSERT INTO ds_account (source_id, account_name, external_account_id, auth_type, credential_ref, status, expires_at) "
            + "VALUES (#{sourceId}, #{accountName}, #{externalAccountId}, #{authType}, #{credentialRef}, #{status}, #{expiresAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SourceAccount account);
}
