package com.ai.tool.controller;

import com.ai.AiRules;
import com.ai.AiTokenCodec;
import com.ai.tool.ReadonlyToolRegistry;
import com.ai.tool.ToolContext;
import com.ai.tool.ToolExecutor;
import com.ai.tool.ToolLimits;
import com.ai.tool.ToolResult;
import com.ai.tool.data.ToolInvocationStore;
import com.ai.tool.security.AiToolGatewayFilter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 内部只读工具入口；先参数校验，再预算/重放仲裁，最后执行。 */
@RestController
@RequestMapping("/internal/ai/tools")
@RequiredArgsConstructor
public class AiToolGatewayController {
    private static final Logger LOG = LoggerFactory.getLogger(AiToolGatewayController.class);
    private final ReadonlyToolRegistry registry;
    private final ToolExecutor executor;
    private final ToolInvocationStore invocations;
    private final ObjectMapper json;

    @PostMapping("/{toolName}")
    public Map<String, Object> call(@PathVariable String toolName,
            @RequestAttribute(AiToolGatewayFilter.CLAIMS) AiTokenCodec.ToolClaims claims,
            @RequestBody JsonNode arguments) {
        if (!ToolLimits.NAME.matcher(toolName).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AI_TOOL_UNKNOWN");
        }
        registry.require(toolName);
        if (!claims.tools().contains(toolName)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "AI_TOOL_SCOPE_DENIED");
        }
        byte[] encoded = json.writeValueAsBytes(arguments);
        if (encoded.length > ToolLimits.MAX_ARGUMENT_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AI_TOOL_INVALID_ARGUMENTS");
        }
        // 参数校验先于预算与审计。
        registry.validate(toolName, arguments);
        long taskId = Long.parseLong(claims.taskId());
        String requestHash = AiRules.hash(new String(encoded, StandardCharsets.UTF_8));
        long invocation = invocations.begin(claims.tenantId(), taskId, claims.userId(), claims.jti(), toolName, requestHash);
        long started = System.nanoTime();
        try {
            Instant asOf = Instant.now();
            ToolResult result = executor.execute(registry, toolName,
                    new ToolContext(claims.tenantId(), claims.userId(), taskId, toolName, asOf), arguments);
            invocations.finish(claims.tenantId(), invocation, "SUCCEEDED", result.facts().size(),
                    elapsedMillis(started), json.writeValueAsString(result.facts()),
                    json.writeValueAsString(result.warnings()), result.asOf(), null);
            LinkedHashMap<String, Object> response = new LinkedHashMap<>();
            response.put("toolName", result.toolName());
            response.put("facts", result.facts());
            response.put("truncated", result.truncated());
            response.put("asOf", result.asOf().toString());
            response.put("warnings", result.warnings());
            return response;
        } catch (RuntimeException failure) {
            try {
                invocations.finish(claims.tenantId(), invocation, "FAILED", 0, elapsedMillis(started),
                        null, null, Instant.now(), safeCode(failure));
            } catch (RuntimeException auditFailure) {
                LOG.error("工具失败审计写入失败 tenant={} task={} tool={} invocation={} errorCode={}",
                        claims.tenantId(), taskId, toolName, invocation, safeCode(failure), auditFailure);
            }
            throw failure;
        }
    }

    private long elapsedMillis(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private String safeCode(RuntimeException failure) {
        if (failure instanceof ResponseStatusException status && status.getReason() != null
                && status.getReason().matches("[A-Z0-9_]{1,80}")) {
            return status.getReason();
        }
        return "AI_TOOL_FAILED";
    }
}
