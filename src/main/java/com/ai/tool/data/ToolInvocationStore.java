package com.ai.tool.data;

import com.ai.tool.ToolLimits;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 工具调用审计、防重放与单任务预算仲裁。 */
@Component
public class ToolInvocationStore {
    private static final Logger LOG = LoggerFactory.getLogger(ToolInvocationStore.class);
    private final JdbcTemplate db;
    private final TransactionTemplate tx;

    private record TaskState(String state, Timestamp expiresAt) {
    }

    public ToolInvocationStore(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = db;
        this.tx = new TransactionTemplate(manager);
        this.tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 执行流程 1锁任务 2核对RUNNING与期限 3核对次数 4插入审计 5递增计数。 */
    public long begin(long tenant, long task, long user, String jti, String tool, String requestHash) {
        Long id = tx.execute(status -> {
            List<TaskState> rows = db.query("SELECT state,expires_at FROM ai_tasks WHERE tenant_id=? AND id=? FOR UPDATE",
                    (row, number) -> new TaskState(row.getString(1), row.getTimestamp(2)), tenant, task);
            if (rows.size() != 1 || !"RUNNING".equals(rows.get(0).state())
                    || rows.get(0).expiresAt() == null || !rows.get(0).expiresAt().toInstant().isAfter(Instant.now())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "AI_TOOL_TASK_INACTIVE");
            }
            Integer count = db.queryForObject("SELECT COUNT(*) FROM ai_tool_invocations WHERE tenant_id=? AND task_id=?",
                    Integer.class, tenant, task);
            if (count != null && count >= ToolLimits.MAX_CALLS_PER_TASK) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "AI_TOOL_BUDGET_EXCEEDED");
            }
            try {
                db.update("INSERT INTO ai_tool_invocations(tenant_id,task_id,user_id,jti,tool_name,request_hash,state)"
                        + " VALUES (?,?,?,?,?,?,'RUNNING')", tenant, task, user, jti, tool, requestHash);
            } catch (DuplicateKeyException replay) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "AI_TOOL_REPLAY");
            }
            Long invocation = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            db.update("UPDATE ai_tasks SET tool_call_count=tool_call_count+1 WHERE tenant_id=? AND id=? AND state='RUNNING'",
                    tenant, task);
            return invocation;
        });
        if (id == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI_STORAGE_UNAVAILABLE");
        return id;
    }

    public void finish(long tenant, long id, String state, int rowCount, long durationMs,
                       String factsJson, String warningsJson, Instant asOf, String errorCode) {
        if (!List.of("SUCCEEDED", "FAILED").contains(state)) throw new IllegalArgumentException("AI_TOOL_INVALID_STATE");
        int duration = (int) Math.min(Integer.MAX_VALUE, Math.max(0, durationMs));
        int changed = db.update("UPDATE ai_tool_invocations SET state=?,row_count=?,duration_ms=?,facts_json=?,warnings_json=?,"
                        + "as_of=?,error_code=? WHERE tenant_id=? AND id=? AND state='RUNNING'",
                state, rowCount, duration, factsJson, warningsJson,
                asOf == null ? null : Timestamp.from(asOf), errorCode, tenant, id);
        if (changed == 0) LOG.warn("工具审计结束未更新 tenant={} invocation={} state={}", tenant, id, state);
    }

    public boolean succeeded(long tenant, long task, String tool) {
        Integer count = db.queryForObject("SELECT COUNT(*) FROM ai_tool_invocations WHERE tenant_id=? AND task_id=?"
                + " AND tool_name=? AND state='SUCCEEDED'", Integer.class, tenant, task, tool);
        return count != null && count > 0;
    }
}
