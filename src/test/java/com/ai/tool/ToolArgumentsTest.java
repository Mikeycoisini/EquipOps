package com.ai.tool;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 参数schema拒绝未知键与隐式类型转换。 */
class ToolArgumentsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object", "additionalProperties", false,
            "required", List.of("equipmentId"),
            "properties", Map.of("equipmentId", Map.of("type", "integer", "minimum", 1, "maximum", 100)));

    @Test
    void acceptsValidInteger() throws Exception {
        assertEquals(12, ((Number) ToolArguments.validate(SCHEMA, JSON.readTree("{\"equipmentId\":12}"), JSON)
                .get("equipmentId")).intValue());
    }

    @Test
    void rejectsUnknownKeyStringAndRange() throws Exception {
        assertThrows(ResponseStatusException.class,
                () -> ToolArguments.validate(SCHEMA, JSON.readTree("{\"equipmentId\":12,\"x\":1}"), JSON));
        assertThrows(ResponseStatusException.class,
                () -> ToolArguments.validate(SCHEMA, JSON.readTree("{\"equipmentId\":\"12\"}"), JSON));
        assertThrows(ResponseStatusException.class,
                () -> ToolArguments.validate(SCHEMA, JSON.readTree("{\"equipmentId\":0}"), JSON));
        assertThrows(ResponseStatusException.class,
                () -> ToolArguments.validate(SCHEMA, JSON.readTree("{}"), JSON));
    }
}
