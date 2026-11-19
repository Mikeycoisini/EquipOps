package com.ai.tool.impl;

import com.ai.tool.ReadonlyTool;
import com.ai.tool.ToolContext;
import com.ai.tool.ToolFacts;
import com.ai.tool.ToolLimits;
import com.ai.tool.ToolResult;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.equipment.service.MaintenanceHistoryService;
import com.equipops.domain.vo.WorkOrderVO;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** 按设备查询已完成维修工单，最多20条。 */
@Component
@RequiredArgsConstructor
public class GetEquipmentHistoryTool implements ReadonlyTool {
    private final MaintenanceHistoryService history;

    @Override
    public String name() {
        return "get_equipment_history";
    }

    @Override
    public String description() {
        return "查询指定设备已完成的维修工单履历，按完成时间倒序，最多20条。只读；不返回人员信息。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "additionalProperties", false,
                "required", List.of("equipmentId"),
                "properties", Map.of("equipmentId", Map.of("type", "integer", "minimum", 1)));
    }

    @Override
    public Set<String> factFields() {
        return Set.of("orderId", "orderNo", "title", "description", "priority", "status", "completedAt", "createdAt");
    }

    @Override
    public ToolResult execute(ToolContext context, JsonNode arguments) {
        long equipmentId = arguments.get("equipmentId").longValue();
        IPage<WorkOrderVO> page = history.getMaintenanceHistory(equipmentId, 1, ToolLimits.MAX_ROWS);
        List<Map<String, Object>> facts = new ArrayList<>();
        for (WorkOrderVO order : page.getRecords()) {
            LinkedHashMap<String, Object> fact = new LinkedHashMap<>();
            ToolFacts.put(fact, "orderId", ToolFacts.id(order.getId()));
            ToolFacts.put(fact, "orderNo", order.getOrderNo());
            ToolFacts.put(fact, "title", order.getTitle());
            ToolFacts.put(fact, "description", ToolFacts.truncate(order.getDescription(), 500));
            ToolFacts.put(fact, "priority", order.getPriority() == null ? null : order.getPriority().name());
            ToolFacts.put(fact, "status", order.getStatus() == null ? null : order.getStatus().name());
            ToolFacts.put(fact, "completedAt", ToolFacts.time(order.getCompletedAt()));
            ToolFacts.put(fact, "createdAt", ToolFacts.time(order.getCreatedAt()));
            facts.add(fact);
        }
        boolean truncated = page.getTotal() > facts.size();
        return new ToolResult(name(), facts, truncated, context.asOf(), List.of());
    }
}
