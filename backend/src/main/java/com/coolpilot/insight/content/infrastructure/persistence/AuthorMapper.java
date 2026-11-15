package com.coolpilot.insight.content.infrastructure.persistence;

import com.coolpilot.insight.content.domain.Author;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AuthorMapper {

    @Select("SELECT * FROM ct_author WHERE source_id = #{sourceId} AND external_author_id = #{externalId}")
    Author selectByExternal(@Param("sourceId") Long sourceId, @Param("externalId") String externalId);

    @Insert("INSERT INTO ct_author (source_id, external_author_id, display_name, profile_url, "
            + "public_profile_json, first_seen_at, last_seen_at) VALUES (#{sourceId}, #{externalAuthorId}, "
            + "#{displayName}, #{profileUrl}, #{publicProfileJson}, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Author author);

    @Update("UPDATE ct_author SET display_name = #{displayName}, last_seen_at = NOW() WHERE id = #{id}")
    int touch(Author author);
}
