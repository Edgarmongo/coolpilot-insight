package com.coolpilot.insight.content.infrastructure.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 平台原始响应归档。按 (source, externalId, hash) 幂等，重复采集不重复存。 */
public interface RawArchiveMapper {

    @Select("SELECT id FROM ct_raw_archive WHERE source_id = #{sourceId} "
            + "AND external_id = #{externalId} AND payload_hash = #{hash}")
    Long selectId(@Param("sourceId") Long sourceId,
                  @Param("externalId") String externalId,
                  @Param("hash") String hash);

    @Insert("INSERT INTO ct_raw_archive (source_id, external_id, content_type, payload, payload_hash) "
            + "VALUES (#{sourceId}, #{externalId}, #{contentType}, #{payload}, #{hash})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ArchiveRow row);

    /** 插入用的简单载体，避免为一张归档表再建一个 domain。 */
    class ArchiveRow {
        public Long id;
        public Long sourceId;
        public String externalId;
        public String contentType;
        public String payload;
        public String hash;
    }
}
