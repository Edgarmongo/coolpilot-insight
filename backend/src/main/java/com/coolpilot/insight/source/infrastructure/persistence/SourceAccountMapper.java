package com.coolpilot.insight.source.infrastructure.persistence;

import com.coolpilot.insight.source.domain.SourceAccount;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface SourceAccountMapper {

    @Select("SELECT * FROM ds_account ORDER BY source_id, id")
    List<SourceAccount> selectAll();

    @Select("SELECT * FROM ds_account WHERE id = #{id}")
    SourceAccount selectById(@Param("id") Long id);

    @Select("SELECT * FROM ds_account WHERE source_id = #{sourceId} AND external_account_id = #{externalId}")
    SourceAccount selectByExternal(@Param("sourceId") Long sourceId,
                                   @Param("externalId") String externalId);

    @Insert("INSERT INTO ds_account (source_id, account_name, external_account_id, auth_type, "
            + "credential_ref, status, auth_status, expires_at, remark) VALUES (#{sourceId}, "
            + "#{accountName}, #{externalAccountId}, #{authType}, #{credentialRef}, #{status}, "
            + "#{authStatus}, #{expiresAt}, #{remark})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SourceAccount a);

    /** 只允许改资料与凭证引用；auth_status 不在此接口内（必须来自验证结果）。 */
    @Update("UPDATE ds_account SET account_name = #{accountName}, credential_ref = #{credentialRef}, "
            + "expires_at = #{expiresAt}, remark = #{remark}, updated_at = NOW() WHERE id = #{id}")
    int updateProfile(SourceAccount a);

    @Update("UPDATE ds_account SET status = #{status}, updated_at = NOW() WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    /** 仅 AccountVerificationService 调用。 */
    @Update("UPDATE ds_account SET auth_status = #{authStatus}, last_verified_at = #{verifiedAt}, "
            + "authorized_at = #{authorizedAt}, last_error_code = #{errorCode}, "
            + "last_error_message = #{errorMessage}, updated_at = NOW() WHERE id = #{id}")
    int updateAuthResult(@Param("id") Long id, @Param("authStatus") String authStatus,
                         @Param("verifiedAt") LocalDateTime verifiedAt,
                         @Param("authorizedAt") LocalDateTime authorizedAt,
                         @Param("errorCode") String errorCode,
                         @Param("errorMessage") String errorMessage);
}
