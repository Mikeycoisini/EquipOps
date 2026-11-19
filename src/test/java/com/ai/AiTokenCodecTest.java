package com.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 资料用途与工具用途凭证隔离。 */
class AiTokenCodecTest {
    private static final String SECRET = "synthetic-shared-secret-at-least-32-characters";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");

    private AiTokenCodec codec() {
        return new AiTokenCodec(SECRET, JSON, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void issuesAndVerifiesQueryToken() {
        String token = codec().issue(1, 2, "9", "query", List.of("7"));
        var claims = codec().verify(token);
        assertEquals(1, claims.tenantId());
        assertEquals("query", claims.scopes().get(0));
        assertEquals(List.of("7"), claims.versionIds());
    }

    @Test
    void issuesAndVerifiesToolToken() {
        String token = codec().issueTools(1, 2, "9", List.of("get_equipment_info"));
        var claims = codec().verifyTool(token);
        assertEquals(9, Long.parseLong(claims.taskId()));
        assertTrue(claims.tools().contains("get_equipment_info"));
    }

    @Test
    void queryTokenCannotCallTools() {
        String query = codec().issue(1, 2, "9", "query", List.of("7"));
        assertThrows(ResponseStatusException.class, () -> codec().verifyTool(query));
    }

    @Test
    void rejectsTamperedAndExpiredTokens() {
        String token = codec().issueTools(1, 2, "9", List.of("get_equipment_info"));
        char last = token.charAt(token.length() - 1);
        String tampered = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');
        assertThrows(ResponseStatusException.class, () -> codec().verifyTool(tampered));
        var expired = new AiTokenCodec(SECRET, JSON, Clock.fixed(NOW.plusSeconds(121), ZoneOffset.UTC));
        assertThrows(ResponseStatusException.class, () -> expired.verifyTool(token));
    }

    @Test
    void rejectsEmptyOrInvalidToolNames() {
        assertThrows(ResponseStatusException.class, () -> codec().issueTools(1, 2, "9", List.of()));
        assertThrows(ResponseStatusException.class, () -> codec().issueTools(1, 2, "9", List.of("Delete-Equipment")));
        assertThrows(ResponseStatusException.class, () -> codec().issueTools(1, 2, "uuid", List.of("get_equipment_info")));
    }
}
