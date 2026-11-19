package com.ai.tool;

import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** 只读工具契约；实现只调用既有Service或AI自有存储。 */
public interface ReadonlyTool {
    String name();

    String description();

    Map<String, Object> inputSchema();

    Set<String> factFields();

    default int maxRows() {
        return ToolLimits.MAX_ROWS;
    }

    ToolResult execute(ToolContext context, JsonNode arguments);
}
