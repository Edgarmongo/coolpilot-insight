package com.coolpilot.insight.collection.infrastructure.persistence;

import com.coolpilot.insight.mapper.BaseCrudMapper;

import com.coolpilot.insight.collection.domain.CollectionTask;

import org.apache.ibatis.annotations.Param;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface CollectionTaskMapper extends BaseCrudMapper<CollectionTask> {

    @Select("SELECT id FROM collection_task WHERE owner_user_id = #{ownerUserId} "
            + "ORDER BY id DESC LIMIT #{limit}")
    List<Long> selectIdsByOwner(@Param("ownerUserId") Long ownerUserId, @Param("limit") int limit);

    @Select("SELECT id FROM collection_task WHERE owner_user_id = #{ownerUserId}")
    List<Long> selectAllIdsByOwner(@Param("ownerUserId") Long ownerUserId);

    @Select("SELECT * FROM collection_task WHERE status = 'PENDING' ORDER BY id ASC LIMIT 1")
    CollectionTask selectPendingOldest();

    /**
     * 原子领取：只有把 PENDING 改成 RUNNING 成功的那一次才返回 1。
     * 对应原先 update(entity, wrapper) 的条件更新语义。
     */
    @org.apache.ibatis.annotations.Update("UPDATE collection_task SET status = 'RUNNING', started_at = #{startedAt} "
            + "WHERE id = #{id} AND status = 'PENDING'")
    int claimPending(@Param("id") Long id, @Param("startedAt") java.time.LocalDateTime startedAt);

    @Select("<script>SELECT * FROM collection_task"
            + "<where><if test='status != null and status.length() > 0'> AND status = #{status}</if>"
            + "<if test='ownerUserId != null'> AND owner_user_id = #{ownerUserId}</if></where>"
            + " ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<CollectionTask> selectPage(@Param("status") String status,
                                    @Param("ownerUserId") Long ownerUserId,
                                    @Param("limit") int limit, @Param("offset") int offset);

    /** 原子闸门：只有 IDLE 的任务能进入 RUNNING，返回 1 表示领取成功。 */
    @Update("UPDATE collection_task SET status = 'RUNNING', started_at = NOW() "
            + "WHERE id = #{id} AND status = 'IDLE'")
    int claimIdle(@Param("id") Long id);

    /** Run 结束后回落到 IDLE 或 PAUSED（不写 Run 的结果）。 */
    @Update("UPDATE collection_task SET status = #{status} WHERE id = #{id}")
    int releaseToIdle(@Param("id") Long id, @Param("status") String status);
}
