package com.coolpilot.insight.collection.infrastructure.persistence;

import com.coolpilot.insight.collection.domain.ColRun;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface ColRunMapper {

    @Select("SELECT * FROM col_run WHERE id = #{id}")
    ColRun selectById(@Param("id") Long id);

    @Select("SELECT * FROM col_run WHERE task_id = #{taskId} ORDER BY run_no DESC LIMIT 1")
    ColRun selectLatestByTask(@Param("taskId") Long taskId);

    @Select("SELECT * FROM col_run WHERE task_id = #{taskId} ORDER BY run_no DESC LIMIT #{limit} OFFSET #{offset}")
    List<ColRun> selectPageByTask(@Param("taskId") Long taskId,
                                  @Param("offset") int offset, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM col_run WHERE task_id = #{taskId}")
    long countByTask(@Param("taskId") Long taskId);

    @Select("SELECT COUNT(*) FROM col_run WHERE task_id = #{taskId} AND status IN ('PENDING','RUNNING','PAUSED')")
    int countActiveByTask(@Param("taskId") Long taskId);

    @Select("SELECT COALESCE(MAX(run_no), 0) FROM col_run WHERE task_id = #{taskId}")
    int maxRunNo(@Param("taskId") Long taskId);

    @Insert("INSERT INTO col_run (task_id, run_no, trigger_type, status, config_snapshot, "
            + "started_at, created_at) VALUES (#{taskId}, #{runNo}, #{triggerType}, #{status}, "
            + "#{configSnapshot}, #{startedAt}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ColRun run);

    @Update("UPDATE col_run SET status = #{status}, finished_at = #{finishedAt}, "
            + "success_count = #{successCount}, failed_count = #{failedCount}, "
            + "total_execution_count = #{totalExecutionCount}, error_summary = #{errorSummary} "
            + "WHERE id = #{id}")
    int updateResult(ColRun run);
}
