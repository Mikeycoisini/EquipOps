package com.ai.tool.impl;

import com.ai.tool.ReadonlyTool;
import com.ai.tool.ToolContext;
import com.ai.tool.ToolFacts;
import com.ai.tool.ToolResult;
import com.equipment.service.EquipmentService;
import com.equipops.domain.vo.EquipmentVO;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** 按设备ID查询当前企业内设备档案。 */
@Component
@RequiredArgsConstructor
public class GetEquipmentInfoTool implements ReadonlyTool {
    private final EquipmentService equipment;

    @Override
    public String name() {
        return "get_equipment_info";
    }

    @Override
    public String description() {
        return "查询当前企业内指定设备的基础档案。只读；跨企业或无厂区权限按不存在返回；不返回负责人信息。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "additionalProperties", false,
                "required", List.of("equipmentId"),
                "properties", Map.of("equipmentId", Map.of("type", "integer", "minimum", 1)));
    }

    @Override
    public Set<String> factFields() {
        return Set.of("equipmentId", "siteId", "assetCode", "name", "model", "location", "category",
                "status", "health", "nextMaintenanceAt", "runtimeHours", "installedAt", "createdAt", "updatedAt");
    }

    @Override
    public ToolResult execute(ToolContext context, JsonNode arguments) {
        long id = arguments.get("equipmentId").longValue();
        EquipmentVO value = equipment.getEquipmentDetail(id);
        if (value == null) return new ToolResult(name(), List.of(), false, context.asOf(), List.of());
        LinkedHashMap<String, Object> fact = new LinkedHashMap<>();
        ToolFacts.put(fact, "equipmentId", ToolFacts.id(value.getId()));
        ToolFacts.put(fact, "siteId", ToolFacts.id(value.getSiteId()));
        ToolFacts.put(fact, "assetCode", value.getAssetCode());
        ToolFacts.put(fact, "name", value.getName());
        ToolFacts.put(fact, "model", value.getModel());
        ToolFacts.put(fact, "location", value.getLocation());
        ToolFacts.put(fact, "category", value.getCategory());
        ToolFacts.put(fact, "status", value.getStatus() == null ? null : value.getStatus().name());
        ToolFacts.put(fact, "health", value.getHealth());
        ToolFacts.put(fact, "nextMaintenanceAt", ToolFacts.date(value.getNextMaintenanceAt()));
        ToolFacts.put(fact, "runtimeHours", value.getRuntimeHours() == null ? null : value.getRuntimeHours().toPlainString());
        ToolFacts.put(fact, "installedAt", ToolFacts.date(value.getInstalledAt()));
        ToolFacts.put(fact, "createdAt", ToolFacts.time(value.getCreatedAt()));
        ToolFacts.put(fact, "updatedAt", ToolFacts.time(value.getUpdatedAt()));
        return new ToolResult(name(), List.of(fact), false, context.asOf(), List.of());
    }
}
