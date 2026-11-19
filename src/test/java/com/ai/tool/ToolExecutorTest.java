package com.ai.tool;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 超时上限与异常脱敏。 */
class ToolExecutorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");

    @Test
    void slowToolReturns504() throws Exception {
        var registry = new ReadonlyToolRegistry(List.of(tool("slow_tool", 5000)), JSON);
        var executor = new ToolExecutor();
        try {
            var failure = assertThrows(ResponseStatusException.class,
                    () -> executor.execute(registry, "slow_tool", context("slow_tool"), JSON.readTree("{}")));
            assertEquals(504, failure.getStatusCode().value());
        } finally {
            executor.close();
        }
    }

    @Test
    void internalFailureIsMasked() throws Exception {
        ReadonlyTool failing = new ReadonlyTool() {
            @Override public String name() { return "fail_tool"; }
            @Override public String description() { return "失败"; }
            @Override public Map<String, Object> inputSchema() {
                return Map.of("type", "object", "additionalProperties", false, "properties", Map.of());
            }
            @Override public Set<String> factFields() { return Set.of("value"); }
            @Override public ToolResult execute(ToolContext context, JsonNode arguments) {
                throw new IllegalStateException("password=secret");
            }
        };
        var registry = new ReadonlyToolRegistry(List.of(failing), JSON);
        var executor = new ToolExecutor();
        try {
            var failure = assertThrows(ResponseStatusException.class,
                    () -> executor.execute(registry, "fail_tool", context("fail_tool"), JSON.readTree("{}")));
            assertEquals(502, failure.getStatusCode().value());
            assertFalse(String.valueOf(failure.getMessage()).contains("secret"));
        } finally {
            executor.close();
        }
    }

    private ReadonlyTool tool(String name, long sleep) {
        return new ReadonlyTool() {
            @Override public String name() { return name; }
            @Override public String description() { return "慢工具"; }
            @Override public Map<String, Object> inputSchema() {
                return Map.of("type", "object", "additionalProperties", false, "properties", Map.of());
            }
            @Override public Set<String> factFields() { return Set.of("value"); }
            @Override public ToolResult execute(ToolContext context, JsonNode arguments) {
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return new ToolResult(name, List.of(), false, context.asOf(), List.of());
            }
        };
    }

    private ToolContext context(String name) {
        return new ToolContext(1, 2, 9, name, NOW);
    }
}
