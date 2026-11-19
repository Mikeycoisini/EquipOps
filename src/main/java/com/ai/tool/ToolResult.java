package com.ai.tool;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 工具原始结果；白名单投影与toolName/asOf由注册表统一补齐。 */
public record ToolResult(String toolName, List<Map<String, Object>> facts, boolean truncated,
                         Instant asOf, List<String> warnings) {
    public ToolResult {
        if (toolName == null || facts == null || asOf == null || warnings == null) {
            throw new IllegalArgumentException("AI_TOOL_INVALID_RESULT");
        }
        facts = facts.stream().map(Map::copyOf).toList();
        warnings = List.copyOf(warnings);
    }
}
