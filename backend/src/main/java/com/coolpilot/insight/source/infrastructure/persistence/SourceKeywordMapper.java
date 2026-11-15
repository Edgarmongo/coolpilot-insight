package com.coolpilot.insight.source.infrastructure.persistence;

import com.coolpilot.insight.source.domain.SourceKeyword;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface SourceKeywordMapper {

    @Select("<script>SELECT * FROM ds_keyword"
            + "<where><if test='ownerUserId != null'> AND owner_user_id = #{ownerUserId}</if>"
            + "<if test='sourceId != null'> AND source_id = #{sourceId}</if></where>"
            + " ORDER BY id DESC</script>")
    List<SourceKeyword> selectList(@Param("ownerUserId") Long ownerUserId,
                                   @Param("sourceId") Long sourceId);

    @Select("SELECT * FROM ds_keyword WHERE id = #{id}")
    SourceKeyword selectById(@Param("id") Long id);

    @Select("SELECT COUNT(*) FROM ds_keyword WHERE source_id = #{sourceId} "
            + "AND owner_user_id = #{ownerUserId} AND normalized = #{normalized}")
    int countByUnique(@Param("sourceId") Long sourceId,
                      @Param("ownerUserId") Long ownerUserId,
                      @Param("normalized") String normalized);

    @Insert("INSERT INTO ds_keyword (source_id, keyword, normalized, owner_user_id, status, discover_policy) "
            + "VALUES (#{sourceId}, #{keyword}, #{normalized}, #{ownerUserId}, #{status}, #{discoverPolicy})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SourceKeyword keyword);

    @Update("UPDATE ds_keyword SET status = #{status}, updated_at = NOW() WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    @Update("UPDATE ds_keyword SET last_discovered_at = NOW() WHERE id = #{id}")
    int markDiscovered(@Param("id") Long id);

    @Delete("DELETE FROM ds_keyword WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
