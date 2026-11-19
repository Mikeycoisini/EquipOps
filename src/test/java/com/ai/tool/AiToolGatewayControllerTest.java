package com.ai.tool;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ai.AiTokenCodec;
import com.ai.tool.controller.AiToolGatewayController;
import com.ai.tool.data.ToolInvocationStore;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 控制器层未知工具、越权、重放与超时映射。 */
class AiToolGatewayControllerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");

    @Test
    void unknownToolIsRejectedBeforeBudget() throws Exception {
        var registry = new ReadonlyToolRegistry(List.of(tool()), JSON);
        var invocations = mock(ToolInvocationStore.class);
        var controller = new AiToolGatewayController(registry, mock(ToolExecutor.class), invocations, JSON);
        var failure = assertThrows(ResponseStatusException.class,
                () -> controller.call("delete_equipment", claims(List.of("fake_tool")), JSON.readTree("{}")));
        assertEquals(400, failure.getStatusCode().value());
        verifyNoInteractions(invocations);
    }

    @Test
    void scopeDeniedDoesNotConsumeBudget() throws Exception {
        var registry = new ReadonlyToolRegistry(List.of(tool()), JSON);
        var invocations = mock(ToolInvocationStore.class);
        var controller = new AiToolGatewayController(registry, mock(ToolExecutor.class), invocations, JSON);
        var failure = assertThrows(ResponseStatusException.class,
                () -> controller.call("fake_tool", claims(List.of("get_equipment_info")), JSON.readTree("{}")));
        assertEquals(403, failure.getStatusCode().value());
        verifyNoInteractions(invocations);
    }

    @Test
    void successfulCallReturnsEnvelope() throws Exception {
        var registry = new ReadonlyToolRegistry(List.of(tool()), JSON);
        var invocations = mock(ToolInvocationStore.class);
        when(invocations.begin(eq(1L), eq(9L), eq(2L), eq("jti"), eq("fake_tool"), anyString())).thenReturn(7L);
        var executor = mock(ToolExecutor.class);
        when(executor.execute(any(), eq("fake_tool"), any(ToolContext.class), any(JsonNode.class)))
                .thenReturn(new ToolResult("fake_tool", List.of(Map.of("value", "v")), false, NOW, List.of()));
        var controller = new AiToolGatewayController(registry, executor, invocations, JSON);
        Map<String, Object> response = controller.call("fake_tool", claims(List.of("fake_tool")), JSON.readTree("{}"));
        assertEquals("fake_tool", response.get("toolName"));
        assertEquals(1, ((List<?>) response.get("facts")).size());
        verify(invocations).finish(eq(1L), eq(7L), eq("SUCCEEDED"), eq(1), anyLong(), anyString(), anyString(),
                any(Instant.class), isNull());
    }

    @Test
    void timeoutMarksFailedAndKeepsStatus() throws Exception {
        var registry = new ReadonlyToolRegistry(List.of(tool()), JSON);
        var invocations = mock(ToolInvocationStore.class);
        when(invocations.begin(anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyString())).thenReturn(7L);
        var executor = mock(ToolExecutor.class);
        when(executor.execute(any(), anyString(), any(ToolContext.class), any(JsonNode.class)))
                .thenThrow(new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "AI_TOOL_TIMEOUT"));
        var controller = new AiToolGatewayController(registry, executor, invocations, JSON);
        var failure = assertThrows(ResponseStatusException.class,
                () -> controller.call("fake_tool", claims(List.of("fake_tool")), JSON.readTree("{}")));
        assertEquals(504, failure.getStatusCode().value());
        verify(invocations).finish(eq(1L), eq(7L), eq("FAILED"), eq(0), anyLong(), isNull(), isNull(),
                any(Instant.class), eq("AI_TOOL_TIMEOUT"));
    }

    private AiTokenCodec.ToolClaims claims(List<String> tools) {
        return new AiTokenCodec.ToolClaims(1, 2, "9", "jti", NOW.plusSeconds(120).getEpochSecond(),
                new LinkedHashSet<>(tools));
    }

    private ReadonlyTool tool() {
        return new ReadonlyTool() {
            @Override public String name() { return "fake_tool"; }
            @Override public String description() { return "测试"; }
            @Override public Map<String, Object> inputSchema() {
                return Map.of("type", "object", "additionalProperties", false, "properties", Map.of());
            }
            @Override public Set<String> factFields() { return Set.of("value"); }
            @Override public ToolResult execute(ToolContext context, JsonNode arguments) {
                return new ToolResult("fake_tool", List.of(), false, context.asOf(), List.of());
            }
        };
    }
}
