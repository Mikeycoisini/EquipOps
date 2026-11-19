package com.ai.tool.data;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** SOP词面检索：只读AI自有片段表，按企业、发布状态和来源限定。 */
@Repository
public class SopSearchRepository {
    public record SopChunk(long chunkId, long versionId, long documentId, String documentTitle,
                           String section, String contentText) {
    }

    private final JdbcTemplate db;

    public SopSearchRepository(JdbcTemplate db) {
        this.db = db;
    }

    public List<SopChunk> search(long tenant, String query, int limit) {
        String escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String like = "%" + escaped + "%";
        return db.query("""
                SELECT c.id, c.version_id, v.document_id, d.title, c.section, c.content_text
                FROM ai_chunks c
                JOIN ai_document_versions v ON v.tenant_id=c.tenant_id AND v.id=c.version_id
                JOIN ai_documents d ON d.tenant_id=v.tenant_id AND d.id=v.document_id
                WHERE c.tenant_id=? AND v.index_state='INDEXED' AND d.state='ACTIVE' AND v.source_type='DOCUMENT'
                  AND (c.content_text LIKE ? OR c.section LIKE ?)
                ORDER BY c.id DESC
                LIMIT ?
                """, (row, number) -> new SopChunk(row.getLong(1), row.getLong(2), row.getLong(3),
                        row.getString(4), row.getString(5), row.getString(6)), tenant, like, like, limit + 1);
    }
}
