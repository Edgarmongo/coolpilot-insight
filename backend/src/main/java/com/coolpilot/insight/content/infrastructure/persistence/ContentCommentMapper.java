package com.coolpilot.insight.content.infrastructure.persistence;

import com.coolpilot.insight.content.domain.ContentComment;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ContentCommentMapper {

    @Select("SELECT COUNT(*) FROM ct_comment WHERE dedup_key = #{dedupKey}")
    int countByDedupKey(@Param("dedupKey") String dedupKey);

    @Select("SELECT COUNT(*) FROM ct_comment WHERE source_id = #{sourceId} "
            + "AND external_comment_id = #{externalId}")
    int countByExternal(@Param("sourceId") Long sourceId, @Param("externalId") String externalId);

    @Select("SELECT COUNT(*) FROM ct_comment WHERE content_id = #{contentId}")
    int countByContent(@Param("contentId") Long contentId);

    @Insert("INSERT INTO ct_comment (content_id, source_id, external_comment_id, parent_external_id, "
            + "is_reply, author_id, nickname, text, likes, ip_region, published_at, dedup_key, dedup_strategy) "
            + "VALUES (#{contentId}, #{sourceId}, #{externalCommentId}, #{parentExternalId}, #{isReply}, "
            + "#{authorId}, #{nickname}, #{text}, #{likes}, #{ipRegion}, #{publishedAt}, #{dedupKey}, "
            + "#{dedupStrategy})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ContentComment comment);

    @Select("SELECT * FROM ct_comment WHERE content_id = #{contentId} ORDER BY id LIMIT #{limit}")
    List<ContentComment> selectByContent(@Param("contentId") Long contentId, @Param("limit") int limit);
}
