package com.coolpilot.insight.collection.infrastructure.persistence;

import com.coolpilot.insight.mapper.BaseCrudMapper;

import com.coolpilot.insight.collection.domain.CollectionTaskItem;

import org.apache.ibatis.annotations.Param;

import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface CollectionTaskItemMapper extends BaseCrudMapper<CollectionTaskItem> {

    @Select("<script>SELECT COUNT(*) FROM collection_task_item WHERE video_id = #{videoId} "
            + "AND task_id IN <foreach collection='taskIds' item='t' open='(' separator=',' close=')'>#{t}</foreach></script>")
    long countByVideoAndTaskIds(@Param("videoId") Long videoId, @Param("taskIds") List<Long> taskIds);

    @Select("<script>SELECT * FROM collection_task_item WHERE task_id IN "
            + "<foreach collection='taskIds' item='t' open='(' separator=',' close=')'>#{t}</foreach></script>")
    List<CollectionTaskItem> selectByTaskIds(@Param("taskIds") List<Long> taskIds);

    @Select("SELECT COUNT(*) FROM collection_task_item WHERE video_id = #{videoId} AND active_flag = 1")
    long countActiveByVideo(@Param("videoId") Long videoId);

    @Select("SELECT * FROM collection_task_item WHERE task_id = #{taskId} ORDER BY id ASC")
    List<CollectionTaskItem> selectByTask(@Param("taskId") Long taskId);

    @Select("SELECT * FROM collection_task_item WHERE task_id = #{taskId} AND status = #{status}")
    List<CollectionTaskItem> selectByTaskAndStatus(@Param("taskId") Long taskId, @Param("status") String status);

    @Select("SELECT * FROM collection_task_item WHERE video_id = #{videoId} ORDER BY id DESC LIMIT 1")
    CollectionTaskItem selectLatestByVideo(@Param("videoId") Long videoId);

    /** 终态释放活动标记。必须显式 SET NULL —— updateById 会跳过 null 字段。 */
    @org.apache.ibatis.annotations.Update("UPDATE collection_task_item SET active_flag = NULL WHERE id = #{id}")
    int clearActiveFlag(@Param("id") Long id);
}
