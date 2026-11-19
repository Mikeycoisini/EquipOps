package com.ai.tool;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 注册表未知工具、投影、截断与事实校验。 */
class ReadonlyToolRegistryTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");

    @Test
    void unknownToolIsRejected() {
        var registry = new ReadonlyToolRegistry(List.of(tool(1)), JSON);
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> registry.require("missing")).getStatusCode().value());
    }

    @Test
    void projectsAndTruncates() throws Exception {
        var registry = new ReadonlyToolRegistry(List.of(tool(25)), JSON);
        ToolResult result = registry.execute("fake_tool", new ToolContext(1, 2, 9, "fake_tool", NOW),
                JSON.readTree("{\"equipmentId\":1}"));
        assertEquals(20, result.facts().size());
        assertTrue(result.truncated());
        assertEquals(Set.of("toolName", "asOf", "value"), result.facts().get(0).keySet());
        assertFalse(result.facts().get(0).containsKey("secret"));
    }

    @Test
    void verifiesFactsAgainstRegisteredToolAndInvocation() {
        var registry = new ReadonlyToolRegistry(List.of(tool(1)), JSON);
        Map<String, Object> fact = Map.of("toolName", "fake_tool", "asOf", NOW.toString(), "value", "v");
        assertFalse(registry.verifyFacts(List.of(fact), tool -> true).dropped());
        assertTrue(registry.verifyFacts(List.of(fact), tool -> false).dropped());
    }

    private ReadonlyTool tool(int count) {
        return new ReadonlyTool() {
            @Override public String name() { return "fake_tool"; }
            @Override public String description() { return "测试"; }
            @Override public Map<String, Object> inputSchema() {
                return Map.of("type", "object", "additionalProperties", false,
                        "required", List.of("equipmentId"),
                        "properties", Map.of("equipmentId", Map.of("type", "integer", "minimum", 1)));
            }
            @Override public Set<String> factFields() { return Set.of("value"); }
            @Override public ToolResult execute(ToolContext context, tools.jackson.databind.JsonNode arguments) {
                List<Map<String, Object>> facts = new ArrayList<>();
                for (int index = 0; index < count; index++) {
                    LinkedHashMap<String, Object> fact = new LinkedHashMap<>();
                    fact.put("value", "v" + index);
                    fact.put("secret", "s");
                    facts.add(fact);
                }
                return new ToolResult("fake_tool", facts, false, context.asOf(), List.of());
            }
        };
    }
}
