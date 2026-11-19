package com.ai;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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

/** 资料、版本、片段与引用原文的权威存储；所有SQL强制tenant_id。 */
@Component
public class AiDocumentStore {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;

    /** 任务发送给Python的最小片段。 */
    public record ChunkRef(long chunkId, long versionId, String section, String text) {
    }

    /** 某资料的可用版本。 */
    public record Version(long id, long documentId, int versionNo) {
    }

    private static final RowMapper<AiViews.Document> DOCUMENT = (row, number) -> new AiViews.Document(
            row.getLong("id"), row.getString("document_code"), row.getString("title"),
            row.getString("state"), null, null, null,
            time(row.getTimestamp("created_at")), time(row.getTimestamp("updated_at")));

    public AiDocumentStore(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = db;
        this.tx = new TransactionTemplate(manager);
        // 读已提交足够支持资料维护。
        this.tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 分页列表；普通成员只看ACTIVE，管理员由服务层决定是否额外看DRAFT。 */
    public Page<AiViews.Document> list(long tenant, int current, int size, String keyword, boolean includeDraft) {
        String like = keyword == null || keyword.isBlank() ? null : "%" + keyword.replace("\\", "\\\\")
                .replace("%", "\\%").replace("_", "\\_") + "%";
        String where = " WHERE tenant_id=? AND state" + (includeDraft ? " IN ('ACTIVE','DRAFT')" : "='ACTIVE'")
                + (like == null ? "" : " AND (title LIKE ? OR document_code LIKE ?)");
        List<Object> params = new ArrayList<>();
        params.add(tenant);
        if (like != null) {
            params.add(like);
            params.add(like);
        }
        Long total = db.queryForObject("SELECT COUNT(*) FROM ai_documents" + where, Long.class, params.toArray());
        int offset = Math.multiplyExact(current - 1, size);
        List<AiViews.Document> records = db.query("SELECT * FROM ai_documents" + where + " ORDER BY id DESC LIMIT ?,?",
                DOCUMENT, append(params, offset, size).toArray());
        for (int index = 0; index < records.size(); index++) {
            records.set(index, withLatest(tenant, records.get(index)));
        }
        Page<AiViews.Document> page = new Page<>(current, size);
        page.setRecords(records);
        page.setTotal(total == null ? 0 : total);
        return page;
    }

    public Optional<AiViews.Document> detail(long tenant, long id, boolean includeDraft) {
        List<AiViews.Document> rows = db.query("SELECT * FROM ai_documents WHERE tenant_id=? AND id=?"
                        + (includeDraft ? " AND state IN ('ACTIVE','DRAFT')" : " AND state='ACTIVE'"), DOCUMENT, tenant, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(withLatest(tenant, rows.get(0)));
    }

    /** 新建资料主记录。 */
    public long create(long tenant, long user, String code, String title) {
        try {
            db.update("INSERT INTO ai_documents(tenant_id,document_code,title,state,created_by) VALUES (?,?,?,'ACTIVE',?)",
                    tenant, code, title, user);
        } catch (DuplicateKeyException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "AI_DOCUMENT_CODE_CONFLICT");
        }
        return db.queryForObject("SELECT id FROM ai_documents WHERE tenant_id=? AND document_code=?", Long.class, tenant, code);
    }

    /** 追加版本并在同一事务写入全部片段。 */
    public long addVersion(long tenant, long documentId, String storageKey, String format,
                           String content, long byteSize) {
        List<AiChunker.Chunk> chunks = AiChunker.chunk(content);
        if (chunks.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AI_DOCUMENT_EMPTY");
        Long versionId = tx.execute(status -> {
            // 锁定资料，避免并发版本号冲突。
            List<Long> documents = db.queryForList("SELECT id FROM ai_documents WHERE tenant_id=? AND id=? AND state='ACTIVE' FOR UPDATE",
                    Long.class, tenant, documentId);
            if (documents.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AI_DOCUMENT_NOT_FOUND");
            Integer latest = db.queryForObject("SELECT COALESCE(MAX(version_no),0) FROM ai_document_versions WHERE tenant_id=? AND document_id=?",
                    Integer.class, tenant, documentId);
            int next = (latest == null ? 0 : latest) + 1;
            db.update("INSERT INTO ai_document_versions(tenant_id,document_id,version_no,content_hash,storage_key,content_text,format,byte_size,source_type,index_state)"
                            + " VALUES (?,?,?,?,?,?,?,?,'DOCUMENT','INDEXED')",
                    tenant, documentId, next, AiRules.hash(content), storageKey, content, format, byteSize);
            Long version = db.queryForObject("SELECT id FROM ai_document_versions WHERE tenant_id=? AND document_id=? AND version_no=?",
                    Long.class, tenant, documentId, next);
            for (AiChunker.Chunk chunk : chunks) {
                db.update("INSERT INTO ai_chunks(tenant_id,version_id,chunk_no,section,content_text,content_hash,index_ref) VALUES (?,?,?,?,?,?,?)",
                        tenant, version, chunk.chunkNo(), chunk.section(), chunk.text(), chunk.hash(),
                        Long.toString(version) + ":" + chunk.chunkNo());
            }
            return version;
        });
        if (versionId == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI_STORAGE_UNAVAILABLE");
        return versionId;
    }

    /** 逻辑删除资料，历史引用保留但不再可检索。 */
    public void delete(long tenant, long documentId) {
        int changed = db.update("UPDATE ai_documents SET state='DELETED' WHERE tenant_id=? AND id=? AND state IN ('ACTIVE','DRAFT')",
                tenant, documentId);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AI_DOCUMENT_NOT_FOUND");
    }

    /** 为提问选择每个资料的最新可用版本。 */
    public List<Version> activeVersions(long tenant, List<Long> documentIds) {
        List<Version> versions = new ArrayList<>();
        for (Long documentId : documentIds) {
            List<Version> rows = db.query("""
                    SELECT v.id,v.document_id,v.version_no FROM ai_document_versions v
                    JOIN ai_documents d ON d.tenant_id=v.tenant_id AND d.id=v.document_id
                    WHERE v.tenant_id=? AND v.document_id=? AND d.state='ACTIVE' AND v.index_state='INDEXED'
                    ORDER BY v.version_no DESC LIMIT 1
                    """, (row, number) -> new Version(row.getLong(1), row.getLong(2), row.getInt(3)), tenant, documentId);
            if (!rows.isEmpty()) versions.add(rows.get(0));
        }
        return versions;
    }

    /** 发送给Python的片段快照；限制总量，避免任务请求无限放大。 */
    public List<ChunkRef> chunks(long tenant, List<Long> versionIds) {
        if (versionIds.isEmpty()) return List.of();
        String in = placeholders(versionIds.size());
        List<Object> params = new ArrayList<>();
        params.add(tenant);
        params.addAll(versionIds);
        return db.query("SELECT id,version_id,section,content_text FROM ai_chunks WHERE tenant_id=? AND version_id IN (" + in + ")"
                        + " ORDER BY version_id,chunk_no LIMIT 500",
                (row, number) -> new ChunkRef(row.getLong(1), row.getLong(2), row.getString(3), row.getString(4)),
                params.toArray());
    }

    /** 按任务所选版本回取权威原文，只允许ACTIVE资料。 */
    public List<AiRules.Citation> citations(long tenant, List<Long> versionIds) {
        if (versionIds.isEmpty()) return List.of();
        String in = placeholders(versionIds.size());
        List<Object> params = new ArrayList<>();
        params.add(tenant);
        params.addAll(versionIds);
        return db.query("""
                SELECT c.id,c.version_id,v.document_id,c.section,c.content_text,v.source_type,v.source_order_id
                FROM ai_chunks c JOIN ai_document_versions v ON v.tenant_id=c.tenant_id AND v.id=c.version_id
                JOIN ai_documents d ON d.tenant_id=v.tenant_id AND d.id=v.document_id
                WHERE c.tenant_id=? AND v.id IN (""" + in + ") AND d.state='ACTIVE' ORDER BY c.id",
                (row, number) -> new AiRules.Citation(Long.toString(row.getLong(1)), Long.toString(row.getLong(2)),
                        Long.toString(row.getLong(3)), row.getString(4), truncate(row.getString(5)), row.getString(6),
                        row.getObject(7) == null ? null : row.getString(7)), params.toArray());
    }

    private AiViews.Document withLatest(long tenant, AiViews.Document document) {
        List<AiViews.Document> rows = db.query("""
                SELECT v.id version_id,v.version_no,v.index_state FROM ai_document_versions v
                WHERE v.tenant_id=? AND v.document_id=? ORDER BY v.version_no DESC LIMIT 1
                """, (row, number) -> new AiViews.Document(document.id(), document.documentCode(), document.title(),
                        document.state(), row.getLong(1), row.getInt(2), row.getString(3),
                        document.createdAt(), document.updatedAt()), tenant, document.id());
        return rows.isEmpty() ? document : rows.get(0);
    }

    private static List<Object> append(List<Object> values, Object... extra) {
        List<Object> result = new ArrayList<>(values);
        java.util.Collections.addAll(result, extra);
        return result;
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 200 ? value : value.substring(0, 200);
    }

    private static String time(Timestamp value) {
        return value == null ? null : value.toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}
