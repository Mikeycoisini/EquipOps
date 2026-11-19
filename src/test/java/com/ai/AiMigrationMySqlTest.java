package com.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 在equipops_a1_*隔离库执行全量Flyway，验证V24/V25结构、唯一键与任务字段。 */
@EnabledIfEnvironmentVariable(named = "AI_MYSQL_TESTS", matches = "true")
class AiMigrationMySqlTest {
    @Test
    void migratesAndCreatesAiToolSchema() throws Exception {
        String url = required("DB_URL");
        String user = required("DB_USER");
        String password = required("DB_PASSWORD");
        String confirm = required("AI_DB_CONFIRM");
        // 只允许隔离库命名。
        assertTrue(confirm.matches("equipops_a1_[A-Za-z0-9_]+"));
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            // 实际库名必须与确认变量一致，防止误连业务库。
            assertEquals(confirm, connection.getCatalog());
        }
        // 执行V1—V25全量迁移。
        Flyway.configure().dataSource(url, user, password)
                .locations("classpath:db/migration").load().migrate();
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            Set<String> tables = tables(connection, confirm);
            assertTrue(tables.containsAll(Set.of("ai_documents", "ai_document_versions", "ai_chunks",
                    "ai_tasks", "ai_citations", "ai_tool_invocations")));
            assertTrue(columns(connection, confirm, "ai_tasks").containsAll(Set.of("business_facts_json", "tool_call_count")));
            assertTrue(columns(connection, confirm, "ai_document_versions").contains("source_order_id"));
            assertTrue(indexes(connection, confirm, "ai_tool_invocations").contains("uk_ai_tool_replay"));
            // 插入最小母数据并验证防重放唯一键。
            String suffix = UUID.randomUUID().toString().replace("-", "");
            long tenant = insertTenant(connection, suffix);
            long userId = insertUser(connection, suffix);
            long task = insertTask(connection, tenant, userId, suffix);
            insertInvocation(connection, tenant, task, userId, suffix);
            assertThrows(SQLIntegrityConstraintViolationException.class,
                    () -> insertInvocation(connection, tenant, task, userId, suffix));
        }
    }

    private Set<String> tables(Connection connection, String schema) throws SQLException {
        return query(connection, "SELECT table_name FROM information_schema.tables WHERE table_schema=?", schema);
    }

    private Set<String> columns(Connection connection, String schema, String table) throws SQLException {
        return query(connection, "SELECT column_name FROM information_schema.columns WHERE table_schema=? AND table_name=?", schema, table);
    }

    private Set<String> indexes(Connection connection, String schema, String table) throws SQLException {
        return query(connection, "SELECT DISTINCT index_name FROM information_schema.statistics WHERE table_schema=? AND table_name=?", schema, table);
    }

    private Set<String> query(Connection connection, String sql, String... values) throws SQLException {
        Set<String> result = new HashSet<>();
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setString(index + 1, values[index]);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(rows.getString(1));
            }
        }
        return result;
    }

    private long insertTenant(Connection connection, String suffix) throws SQLException {
        try (var statement = connection.prepareStatement(
                "INSERT INTO tenants(tenant_code,name) VALUES (?, 'A9 migration tenant')", Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, "a9-" + suffix);
            statement.executeUpdate();
            return generated(statement);
        }
    }

    private long insertUser(Connection connection, String suffix) throws SQLException {
        try (var statement = connection.prepareStatement(
                "INSERT INTO users(account,password_hash,display_name) VALUES (?, 'synthetic-hash', 'A9 migration user')",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, "a9-" + suffix);
            statement.executeUpdate();
            return generated(statement);
        }
    }

    private long insertTask(Connection connection, long tenant, long user, String suffix) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO ai_tasks(tenant_id,created_by,request_id,request_hash,question_text,selected_versions,state,expires_at)
                VALUES (?,?,?,?, 'V24/V25 migration test', '[]', 'RUNNING', DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 10 MINUTE))
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, tenant);
            statement.setLong(2, user);
            statement.setString(3, "req-" + suffix);
            statement.setString(4, "a".repeat(64));
            statement.executeUpdate();
            return generated(statement);
        }
    }

    private void insertInvocation(Connection connection, long tenant, long task, long user, String suffix) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO ai_tool_invocations(tenant_id,task_id,user_id,jti,tool_name,request_hash,state)
                VALUES (?,?,?,?,'get_equipment_info',?,'RUNNING')
                """)) {
            statement.setLong(1, tenant);
            statement.setLong(2, task);
            statement.setLong(3, user);
            statement.setString(4, "jti-" + suffix);
            statement.setString(5, "b".repeat(64));
            statement.executeUpdate();
        }
    }

    private long generated(java.sql.PreparedStatement statement) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            assertTrue(keys.next());
            return keys.getLong(1);
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
