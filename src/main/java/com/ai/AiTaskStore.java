package com.ai;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 任务状态机与幂等仲裁；所有状态迁移使用条件更新。 */
@Component
public class AiTaskStore {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public record Task(long id, long tenantId, long userId, String requestId, List<String> selectedVersions,
                       String state, String question, String answer, List<String> warnings,
                       String businessFactsJson, Instant expiresAt, long version, String errorCode,
                       String createdAt, String updatedAt) {
    }

    public record Created(Task task, boolean duplicate) {
    }

    private RowMapper<Task> mapper() {
        return (row, number) -> {
        // selected_versions由Java写入，读取时容忍空值。
        List<String> versions;
        try {
            @SuppressWarnings("unchecked")
            List<String> parsed = row.getString("selected_versions") == null ? List.of()
                    : (List<String>) json.readValue(row.getString("selected_versions"), List.class);
            versions = parsed.stream().map(String::valueOf).toList();
        } catch (RuntimeException failure) {
            versions = List.of();
        }
        // 警告与事实保持原JSON，由视图层解析。
        List<String> warnings;
        try {
            @SuppressWarnings("unchecked")
            List<String> parsed = row.getString("warnings_json") == null ? List.of()
                    : (List<String>) json.readValue(row.getString("warnings_json"), List.class);
            warnings = parsed.stream().map(String::valueOf).toList();
        } catch (RuntimeException failure) {
            warnings = List.of();
        }
        return new Task(row.getLong("id"), row.getLong("tenant_id"), row.getLong("created_by"),
                row.getString("request_id"), versions, row.getString("state"), row.getString("question_text"),
                row.getString("answer_text"), warnings, row.getString("business_facts_json"),
                row.getTimestamp("expires_at").toInstant(), row.getLong("version"), row.getString("error_code"),
                time(row.getTimestamp("created_at")), time(row.getTimestamp("updated_at")));
        };
    }

    public AiTaskStore(JdbcTemplate db, PlatformTransactionManager manager, ObjectMapper json) {
        this.db = db;
        this.json = json;
        this.tx = new TransactionTemplate(manager);
        // 读已提交配合条件更新足够。
        this.tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 执行流程 1尝试插入 2重复时比较请求哈希 3返回原任务或409。 */
    public Created create(long tenant, long user, String requestId, String hash, String question,
                          List<Long> versionIds, Instant expiresAt) {
        try {
            db.update("INSERT INTO ai_tasks(tenant_id,created_by,request_id,request_hash,question_text,selected_versions,state,expires_at)"
                            + " VALUES (?,?,?,?,?,?,'QUEUED',?)",
                    tenant, user, requestId, hash, question, json.writeValueAsString(versionIds), Timestamp.from(expiresAt));
        } catch (DuplicateKeyException duplicate) {
            Task existing = db.query("SELECT * FROM ai_tasks WHERE tenant_id=? AND request_id=?",
                    mapper(), tenant, requestId).get(0);
            String existingHash = db.queryForObject("SELECT request_hash FROM ai_tasks WHERE tenant_id=? AND request_id=?",
                    String.class, tenant, requestId);
            if (!hash.equals(existingHash)) throw new ResponseStatusException(HttpStatus.CONFLICT, "AI_IDEMPOTENCY_CONFLICT");
            return new Created(existing, true);
        }
        Long id = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        Task created = get(tenant, user, id).orElseThrow();
        return new Created(created, false);
    }

    /** QUEUED→RUNNING，绑定本次执行的jti。 */
    public Optional<Task> start(long tenant, long id, String jti) {
        int changed = db.update("UPDATE ai_tasks SET state='RUNNING',credential_jti=?,version=version+1"
                + " WHERE tenant_id=? AND id=? AND state='QUEUED' AND expires_at>NOW(6)", jti, tenant, id);
        if (changed != 1) return Optional.empty();
        return db.query("SELECT * FROM ai_tasks WHERE tenant_id=? AND id=?", mapper(), tenant, id).stream().findFirst();
    }

    /** 成功或失败落库；晚到结果因状态非RUNNING而丢弃。 */
    public void finish(long tenant, long id, String state, String answer, String warningsJson,
                       String businessFactsJson, List<AiRules.Citation> citations, String errorCode) {
        if (!List.of("SUCCEEDED", "INSUFFICIENT_EVIDENCE", "FAILED").contains(state)) {
            throw new IllegalArgumentException("AI_INVALID_STATE");
        }
        tx.executeWithoutResult(status -> {
            int changed = db.update("UPDATE ai_tasks SET state=?,answer_text=?,warnings_json=?,business_facts_json=?,"
                            + "error_code=?,version=version+1 WHERE tenant_id=? AND id=? AND state='RUNNING'",
                    state, answer, warningsJson, businessFactsJson, errorCode, tenant, id);
            // 取消或过期后不写引用。
            if (changed != 1) return;
            db.update("DELETE FROM ai_citations WHERE tenant_id=? AND task_id=?", tenant, id);
            int sequence = 1;
            for (AiRules.Citation citation : citations) {
                db.update("INSERT INTO ai_citations(tenant_id,task_id,chunk_id,citation_no,quote_hash) VALUES (?,?,?,?,?)",
                        tenant, id, Long.parseLong(citation.chunkId()), sequence++, AiRules.hash(citation.quote() == null ? "" : citation.quote()));
            }
        });
    }

    /** 取消QUEUED/RUNNING任务；终态返回409。 */
    public void cancel(long tenant, long user, long id) {
        Optional<Task> task = get(tenant, user, id);
        if (task.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AI_TASK_NOT_FOUND");
        int changed = db.update("UPDATE ai_tasks SET state='CANCELLED',version=version+1"
                + " WHERE tenant_id=? AND id=? AND created_by=? AND state IN ('QUEUED','RUNNING')", tenant, id, user);
        if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "AI_TASK_STATE_CONFLICT");
    }

    /** 看门狗：超时任务置EXPIRED。 */
    public int expire() {
        return db.update("UPDATE ai_tasks SET state='EXPIRED',version=version+1"
                + " WHERE state IN ('QUEUED','RUNNING') AND expires_at<=NOW(6)");
    }

    public Optional<Task> get(long tenant, long user, long id) {
        return db.query("SELECT * FROM ai_tasks WHERE tenant_id=? AND id=? AND created_by=?", mapper(), tenant, id, user)
                .stream().findFirst();
    }

    public List<Long> citationIds(long tenant, long taskId) {
        return db.queryForList("SELECT chunk_id FROM ai_citations WHERE tenant_id=? AND task_id=? ORDER BY citation_no",
                Long.class, tenant, taskId);
    }

    private static String time(Timestamp value) {
        return value == null ? null : value.toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}
