package com.coolpilot.insight.mapper;

import com.coolpilot.insight.entity.Keyword;

import org.apache.ibatis.annotations.Param;

import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface KeywordMapper extends BaseCrudMapper<Keyword> {

    @Select("SELECT * FROM keyword WHERE owner_user_id = #{ownerUserId} "
            + "AND normalized = #{normalized} LIMIT 1")
    Keyword selectByOwnerAndNormalized(@Param("ownerUserId") Long ownerUserId,
                                       @Param("normalized") String normalized);

    @Select("<script>SELECT * FROM keyword"
            + "<if test='ownerUserId != null'> WHERE owner_user_id = #{ownerUserId}</if>"
            + " ORDER BY id DESC</script>")
    List<Keyword> selectOrdered(@Param("ownerUserId") Long ownerUserId);
}
