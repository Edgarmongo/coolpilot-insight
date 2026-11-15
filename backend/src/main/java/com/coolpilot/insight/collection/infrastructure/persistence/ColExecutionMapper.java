package com.coolpilot.insight.collection.infrastructure.persistence;

import com.coolpilot.insight.collection.domain.ColExecution;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface ColExecutionMapper {

    @Select("SELECT * FROM col_execution WHERE id = #{id}")
    ColExecution selectById(@Param("id") Long id);

    @Select("SELECT * FROM col_execution WHERE run_id = #{runId} ORDER BY id")
    List<ColExecution> selectByRun(@Param("runId") Long runId);

    /** 该 Run 内某 Item 已尝试次数（按 idem_key 前缀统计，口径可靠，不依赖内存计数）。 */
    @Select("SELECT COUNT(*) FROM col_execution WHERE idem_key LIKE CONCAT(#{prefix}, '%')")
    int countAttempts(@Param("prefix") String prefix);

    @Insert("INSERT INTO col_execution (run_id, source_id, account_id, auth_mode, policy_id, capability, "
            + "status, attempt_no, idem_key, started_at) VALUES (#{runId}, #{sourceId}, #{accountId}, "
            + "#{authMode}, #{policyId}, #{capability}, #{status}, #{attemptNo}, #{idemKey}, #{startedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ColExecution e);

    @Update("UPDATE col_execution SET status = #{status}, finished_at = #{finishedAt}, "
            + "request_count = #{requestCount}, fetched_count = #{fetchedCount}, "
            + "inserted_count = #{insertedCount}, duplicate_count = #{duplicateCount}, "
            + "failed_count = #{failedCount}, failure_type = #{failureType}, "
            + "error_code = #{errorCode}, error_message = #{errorMessage}, "
            + "adapter_version = #{adapterVersion} WHERE id = #{id}")
    int updateResult(ColExecution e);
}
