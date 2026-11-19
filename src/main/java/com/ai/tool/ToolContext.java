package com.ai.tool;

import java.time.Instant;

/** 已验证工具上下文；身份来自凭证和数据库复核，不来自模型参数。 */
public record ToolContext(long tenantId, long userId, long taskId, String toolName, Instant asOf) {
    public ToolContext {
        if (tenantId <= 0 || userId <= 0 || taskId <= 0) throw new IllegalArgumentException("AI_TOOL_INVALID_CONTEXT");
        if (toolName == null || !ToolLimits.NAME.matcher(toolName).matches()) throw new IllegalArgumentException("AI_TOOL_INVALID_CONTEXT");
        if (asOf == null) throw new IllegalArgumentException("AI_TOOL_INVALID_CONTEXT");
    }
}
