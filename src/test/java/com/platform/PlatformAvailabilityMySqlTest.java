package com.platform;

import com.auth.service.CurrentAccessService;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "A8_MYSQL_TESTS", matches = "true")
class PlatformAvailabilityMySqlTest {
    @Autowired PlatformAvailabilityService alerts;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CurrentAccessService access;
    @TempDir Path temporary;

    @Test
    void importsOnceKeepsRecoverySeparateAndRestrictsReceiptAndActions() throws Exception {
        assertEquals("equipops_a8_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
        long owner = operator("a8-owner");
        long other = operator("a8-other");
        Path evidence = temporary.resolve("evidence #1.sqlite");
        var started = Instant.now().minusSeconds(1000);
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + evidence.toUri());
             var sql = db.createStatement()) {
            sql.execute("CREATE TABLE probe_identity(id INTEGER, stream_id TEXT)");
            sql.execute("INSERT INTO probe_identity VALUES (1,'20b19dd1-fbdd-4811-85ac-d8bd104f66be')");
            sql.execute("CREATE TABLE probe_state(id INTEGER, port INTEGER)");
            sql.execute("INSERT INTO probe_state VALUES (1,9093)");
            sql.execute("CREATE TABLE probe_events(id INTEGER, kind TEXT, evidence TEXT, sampled_at TEXT)");
            sql.execute("INSERT INTO probe_events VALUES (1,'OPEN','CONNECT_FAILED','" + started + "')");
            when(access.requireEnabledUserId()).thenReturn(owner);
            alerts.importEvidence(evidence, 9093, owner, 300);
            alerts.importEvidence(evidence, 9093, owner, 300);
            assertEquals(1, alerts.list(true, Long.MAX_VALUE).size());
            alerts.act(1, PlatformAvailabilityController.Action.ACKNOWLEDGE);
            assertEquals(409, assertThrows(ResponseStatusException.class,
                    () -> alerts.act(1, PlatformAvailabilityController.Action.RESOLVE)).getStatusCode().value());
            sql.execute("INSERT INTO probe_events VALUES (2,'RECOVERED','UP','" + started.plusSeconds(10) + "')");
            sql.execute("INSERT INTO probe_events VALUES (3,'OPEN','TIMEOUT','" + started.plusSeconds(20) + "')");
            sql.execute("INSERT INTO probe_events VALUES (5,'OPEN','TIMEOUT','" + started.plusSeconds(320) + "')");
            assertThrows(IllegalArgumentException.class, () -> alerts.importEvidence(evidence, 9093, owner, 300));
            assertNull(alerts.list(true, Long.MAX_VALUE).get(0).recoveredAt());
            sql.execute("INSERT INTO probe_events VALUES (4,'RECOVERED','UP','" + started.plusSeconds(30) + "')");
            alerts.importEvidence(evidence, 9093, owner, 300);
            assertEquals(3, alerts.list(false, Long.MAX_VALUE).size());
            sql.execute("DELETE FROM probe_events WHERE id > 3");
            assertThrows(IllegalArgumentException.class, () -> alerts.importEvidence(evidence, 9093, owner, 300));
            assertEquals(2, alerts.list(true, Long.MAX_VALUE).size());
            alerts.act(1, PlatformAvailabilityController.Action.READ);
            alerts.act(1, PlatformAvailabilityController.Action.RESOLVE);
            alerts.act(1, PlatformAvailabilityController.Action.RESOLVE);
            when(access.requireEnabledUserId()).thenReturn(other);
            assertTrue(alerts.list(true, Long.MAX_VALUE).isEmpty());
            assertEquals(404, assertThrows(ResponseStatusException.class,
                    () -> alerts.act(1, PlatformAvailabilityController.Action.READ)).getStatusCode().value());
            jdbc.update("UPDATE platform_operator_roles SET enabled=0 WHERE user_id=?", other);
            assertEquals(403, assertThrows(ResponseStatusException.class,
                    () -> alerts.list(false, Long.MAX_VALUE)).getStatusCode().value());
        }
    }

    private long operator(String account) {
        jdbc.update("INSERT INTO users(account,password_hash,display_name) VALUES (?,'fixture',?)", account, account);
        long id = jdbc.queryForObject("SELECT id FROM users WHERE account=?", Long.class, account);
        jdbc.update("INSERT INTO platform_operator_roles (user_id,role_code) VALUES (?,'PLATFORM_OPERATOR')", id);
        return id;
    }
}
