package com.ai.tool.impl;

import com.ai.tool.ReadonlyTool;
import com.ai.tool.ToolContext;
import com.ai.tool.ToolFacts;
import com.ai.tool.ToolLimits;
import com.ai.tool.ToolResult;
import com.equipment.service.EquipmentSparePartService;
import com.equipops.domain.vo.EquipmentSparePartVO;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** 按设备查询适用且启用的备件关系。 */
@Component
@RequiredArgsConstructor
public class SearchSparePartsTool implements ReadonlyTool {
    private final EquipmentSparePartService relations;

    @Override
    public String name() {
        return "search_spare_parts";
    }

    @Override
    public String description() {
        return "查询指定设备适用的备件及适配关系来源，只返回本企业启用备件，最多20条。适配关系不代表有库存。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "additionalProperties", false,
                "required", List.of("equipmentId"),
                "properties", Map.of("equipmentId", Map.of("type", "integer", "minimum", 1)));
    }

    @Override
    public Set<String> factFields() {
        return Set.of("equipmentId", "sparePartId", "sparePartCode", "sparePartName", "sparePartSpec",
                "sparePartUnit", "relationId", "relationCreatedAt");
    }

    @Override
    public ToolResult execute(ToolContext context, JsonNode arguments) {
        long equipmentId = arguments.get("equipmentId").longValue();
        List<EquipmentSparePartVO> rows = relations.listRelations(equipmentId);
        List<Map<String, Object>> facts = new ArrayList<>();
        boolean truncated = false;
        for (EquipmentSparePartVO relation : rows) {
            if (!Boolean.TRUE.equals(relation.getSparePartEnabled())) {
                truncated = true;
                continue;
            }
            if (facts.size() >= ToolLimits.MAX_ROWS) {
                truncated = true;
                break;
            }
            LinkedHashMap<String, Object> fact = new LinkedHashMap<>();
            ToolFacts.put(fact, "equipmentId", ToolFacts.id(relation.getEquipmentId()));
            ToolFacts.put(fact, "sparePartId", ToolFacts.id(relation.getSparePartId()));
            ToolFacts.put(fact, "sparePartCode", relation.getSparePartCode());
            ToolFacts.put(fact, "sparePartName", relation.getSparePartName());
            ToolFacts.put(fact, "sparePartSpec", relation.getSparePartSpec());
            ToolFacts.put(fact, "sparePartUnit", relation.getSparePartUnit());
            ToolFacts.put(fact, "relationId", ToolFacts.id(relation.getId()));
            ToolFacts.put(fact, "relationCreatedAt", ToolFacts.time(relation.getCreatedAt()));
            facts.add(fact);
        }
        return new ToolResult(name(), facts, truncated, context.asOf(), List.of());
    }
}
