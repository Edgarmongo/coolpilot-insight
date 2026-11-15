package com.coolpilot.insight.content.infrastructure.persistence;

import com.coolpilot.insight.content.domain.Content;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface ContentMapper {

    @Select("SELECT * FROM ct_content WHERE source_id = #{sourceId} AND external_content_id = #{externalId}")
    Content selectByExternal(@Param("sourceId") Long sourceId, @Param("externalId") String externalId);

    @Select("SELECT * FROM ct_content WHERE id = #{id}")
    Content selectById(@Param("id") Long id);

    @Insert("INSERT INTO ct_content (source_id, external_content_id, content_type, title, content_text, "
            + "source_url, author_id, raw_archive_id, content_hash, normalize_status, published_at, "
            + "collected_at, metrics_json) VALUES (#{sourceId}, #{externalContentId}, #{contentType}, "
            + "#{title}, #{contentText}, #{sourceUrl}, #{authorId}, #{rawArchiveId}, #{contentHash}, "
            + "#{normalizeStatus}, #{publishedAt}, #{collectedAt}, #{metricsJson})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Content content);

    @Update("UPDATE ct_content SET title = #{title}, content_text = #{contentText}, "
            + "author_id = #{authorId}, raw_archive_id = #{rawArchiveId}, content_hash = #{contentHash}, "
            + "normalize_status = #{normalizeStatus}, published_at = #{publishedAt}, "
            + "metrics_json = #{metricsJson}, updated_at = NOW() WHERE id = #{id}")
    int updateNormalized(Content content);

    @Update("UPDATE ct_content SET normalize_status = #{status} WHERE id = #{id}")
    int updateNormalizeStatus(@Param("id") Long id, @Param("status") String status);
}
