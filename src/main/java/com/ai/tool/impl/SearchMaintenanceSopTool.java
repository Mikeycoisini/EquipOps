package com.ai.tool.impl;

import com.ai.tool.ReadonlyTool;
import com.ai.tool.ToolContext;
import com.ai.tool.ToolFacts;
import com.ai.tool.ToolResult;
import com.ai.tool.data.SopSearchRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** 在当前企业已发布资料中做词面SOP检索。 */
@Component
@RequiredArgsConstructor
public class SearchMaintenanceSopTool implements ReadonlyTool {
    private final SopSearchRepository repository;

    @Override
    public String name() {
        return "search_maintenance_sop";
    }

    @Override
    public String description() {
        return "检索当前企业已发布维修规范片段，返回chunkId和摘录。词面匹配，不是语义检索。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "additionalProperties", false,
                "required", List.of("query"),
                "properties", Map.of(
                        "query", Map.of("type", "string", "minLength", 1, "maxLength", 100),
                        "limit", Map.of("type", "integer", "minimum", 1, "maximum", 10)));
    }

    @Override
    public Set<String> factFields() {
        return Set.of("chunkId", "documentId", "versionId", "documentTitle", "section", "snippet");
    }

    @Override
    public ToolResult execute(ToolContext context, JsonNode arguments) {
        String query = arguments.get("query").asString();
        int limit = arguments.has("limit") ? arguments.get("limit").intValue() : 5;
        List<SopSearchRepository.SopChunk> rows = repository.search(context.tenantId(), query, limit);
        int count = Math.min(rows.size(), limit);
        List<Map<String, Object>> facts = new ArrayList<>();
        for (SopSearchRepository.SopChunk chunk : rows.subList(0, count)) {
            LinkedHashMap<String, Object> fact = new LinkedHashMap<>();
            ToolFacts.put(fact, "chunkId", Long.toString(chunk.chunkId()));
            ToolFacts.put(fact, "documentId", Long.toString(chunk.documentId()));
            ToolFacts.put(fact, "versionId", Long.toString(chunk.versionId()));
            ToolFacts.put(fact, "documentTitle", chunk.documentTitle());
            ToolFacts.put(fact, "section", chunk.section());
            ToolFacts.put(fact, "snippet", ToolFacts.truncate(chunk.contentText(), 500));
            facts.add(fact);
        }
        return new ToolResult(name(), facts, rows.size() > count, context.asOf(), List.of());
    }
}
