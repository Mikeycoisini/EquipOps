package com.ai.tool;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 白名单注册表：工具名、参数、行数、字段与事实校验的唯一入口。 */
@Component
public class ReadonlyToolRegistry {
    private final Map<String, ReadonlyTool> tools;
    private final ObjectMapper json;

    public record Verified(List<Object> facts, boolean dropped) {
        public Verified {
            facts = List.copyOf(facts);
        }
    }

    public ReadonlyToolRegistry(List<ReadonlyTool> discovered, ObjectMapper json) {
        this.json = json;
        List<ReadonlyTool> sorted = new ArrayList<>(discovered);
        sorted.sort((left, right) -> left.name().compareTo(right.name()));
        Map<String, ReadonlyTool> registered = new LinkedHashMap<>();
        for (ReadonlyTool tool : sorted) {
            if (tool == null || tool.name() == null || !ToolLimits.NAME.matcher(tool.name()).matches()
                    || registered.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalStateException("AI_TOOL_REGISTRATION_INVALID");
            }
            if (tool.maxRows() < 1 || tool.maxRows() > ToolLimits.MAX_ROWS) throw new IllegalStateException("AI_TOOL_REGISTRATION_INVALID");
            if (tool.factFields() == null || tool.factFields().isEmpty()
                    || tool.factFields().contains("toolName") || tool.factFields().contains("asOf")
                    || tool.factFields().stream().anyMatch(name -> name == null || name.isBlank())) {
                throw new IllegalStateException("AI_TOOL_REGISTRATION_INVALID");
            }
            if (tool.inputSchema() == null || !"object".equals(tool.inputSchema().get("type"))) {
                throw new IllegalStateException("AI_TOOL_REGISTRATION_INVALID");
            }
        }
        this.tools = Map.copyOf(registered);
    }

    public List<String> names() {
        return List.copyOf(tools.keySet());
    }

    public ReadonlyTool require(String name) {
        ReadonlyTool tool = name == null ? null : tools.get(name);
        if (tool == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AI_TOOL_UNKNOWN");
        return tool;
    }

    public void validate(String name, JsonNode arguments) {
        ToolArguments.validate(require(name).inputSchema(), arguments, json);
    }

    /** 执行流程 1参数校验 2执行 3白名单投影 4截断 5补toolName/asOf。 */
    public ToolResult execute(String name, ToolContext context, JsonNode arguments) {
        ReadonlyTool tool = require(name);
        ToolArguments.validate(tool.inputSchema(), arguments, json);
        ToolResult raw = tool.execute(context, arguments);
        if (raw == null || !name.equals(raw.toolName()) || raw.asOf() == null) throw invalidResult();
        Set<String> allowed = tool.factFields();
        int limit = tool.maxRows();
        List<Map<String, Object>> facts = new ArrayList<>();
        boolean truncated = raw.truncated() || raw.facts().size() > limit;
        for (Map<String, Object> fact : raw.facts()) {
            if (facts.size() >= limit) break;
            if (fact == null) throw invalidResult();
            LinkedHashMap<String, Object> projected = new LinkedHashMap<>();
            projected.put("toolName", name);
            projected.put("asOf", raw.asOf().toString());
            for (Map.Entry<String, Object> entry : fact.entrySet()) {
                if (allowed.contains(entry.getKey()) && entry.getValue() != null) projected.put(entry.getKey(), entry.getValue());
            }
            facts.add(Map.copyOf(projected));
        }
        List<String> warnings = raw.warnings().stream()
                .filter(value -> value != null && value.matches("[A-Z0-9_]{1,80}"))
                .limit(ToolLimits.MAX_WARNINGS).toList();
        return new ToolResult(name, facts, truncated, raw.asOf(), warnings);
    }

    /** 执行流程 1限制数量 2核对注册与白名单 3核对成功调用证据。 */
    public Verified verifyFacts(List<Object> supplied, Predicate<String> succeeded) {
        if (supplied == null || supplied.isEmpty()) return new Verified(List.of(), false);
        List<Object> checked = new ArrayList<>();
        boolean dropped = supplied.size() > ToolLimits.MAX_ROWS;
        for (Object item : supplied.subList(0, Math.min(supplied.size(), ToolLimits.MAX_ROWS))) {
            if (!(item instanceof Map<?, ?> map)) {
                dropped = true;
                continue;
            }
            Object rawName = map.get("toolName");
            if (!(rawName instanceof String toolName) || !tools.containsKey(toolName)) {
                dropped = true;
                continue;
            }
            Object rawAsOf = map.get("asOf");
            if (!(rawAsOf instanceof String asOf)) {
                dropped = true;
                continue;
            }
            try {
                Instant.parse(asOf);
            } catch (RuntimeException invalidTime) {
                dropped = true;
                continue;
            }
            Set<String> allowed = new LinkedHashSet<>(tools.get(toolName).factFields());
            allowed.add("toolName");
            allowed.add("asOf");
            boolean valid = true;
            for (Object key : map.keySet()) {
                if (!(key instanceof String name) || !allowed.contains(name)) {
                    valid = false;
                    break;
                }
            }
            if (!valid || !succeeded.test(toolName)) {
                dropped = true;
                continue;
            }
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put((String) entry.getKey(), entry.getValue());
            }
            checked.add(Map.copyOf(copy));
        }
        return new Verified(checked, dropped);
    }

    private ResponseStatusException invalidResult() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI_TOOL_INVALID_RESULT");
    }
}
