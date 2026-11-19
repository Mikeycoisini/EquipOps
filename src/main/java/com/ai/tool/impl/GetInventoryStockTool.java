package com.ai.tool.impl;

import com.ai.tool.ReadonlyTool;
import com.ai.tool.ToolContext;
import com.ai.tool.ToolFacts;
import com.ai.tool.ToolLimits;
import com.ai.tool.ToolResult;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.equipops.domain.dto.inventory.WarehouseStockPageQueryDto;
import com.equipops.domain.vo.WarehouseStockVO;
import com.inventory.service.WarehouseStockService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** 查询备件实时库存余额。 */
@Component
@RequiredArgsConstructor
public class GetInventoryStockTool implements ReadonlyTool {
    private final WarehouseStockService stocks;

    @Override
    public String name() {
        return "get_inventory_stock";
    }

    @Override
    public String description() {
        return "查询指定备件的实时账面余额，可选仓库，最多20条。数量为三位小数字符串；不返回成本价和安全库存。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "additionalProperties", false,
                "required", List.of("sparePartId"),
                "properties", Map.of(
                        "sparePartId", Map.of("type", "integer", "minimum", 1),
                        "warehouseId", Map.of("type", "integer", "minimum", 1)));
    }

    @Override
    public Set<String> factFields() {
        return Set.of("warehouseId", "warehouseCode", "warehouseName", "sparePartId", "partCode", "partName",
                "spec", "unit", "quantity", "lowStock", "updatedAt");
    }

    @Override
    public ToolResult execute(ToolContext context, JsonNode arguments) {
        WarehouseStockPageQueryDto query = new WarehouseStockPageQueryDto();
        query.setCurrent(1);
        query.setSize(ToolLimits.MAX_ROWS);
        query.setSparePartId(arguments.get("sparePartId").longValue());
        if (arguments.has("warehouseId")) query.setWarehouseId(arguments.get("warehouseId").longValue());
        IPage<WarehouseStockVO> page = stocks.getStockPage(query);
        List<Map<String, Object>> facts = new ArrayList<>();
        for (WarehouseStockVO stock : page.getRecords()) {
            LinkedHashMap<String, Object> fact = new LinkedHashMap<>();
            ToolFacts.put(fact, "warehouseId", ToolFacts.id(stock.getWarehouseId()));
            ToolFacts.put(fact, "warehouseCode", stock.getWarehouseCode());
            ToolFacts.put(fact, "warehouseName", stock.getWarehouseName());
            ToolFacts.put(fact, "sparePartId", ToolFacts.id(stock.getSparePartId()));
            ToolFacts.put(fact, "partCode", stock.getPartCode());
            ToolFacts.put(fact, "partName", stock.getPartName());
            ToolFacts.put(fact, "spec", stock.getSpec());
            ToolFacts.put(fact, "unit", stock.getUnit());
            ToolFacts.put(fact, "quantity", ToolFacts.quantity(stock.getQuantity()));
            ToolFacts.put(fact, "lowStock", stock.getLowStock());
            ToolFacts.put(fact, "updatedAt", ToolFacts.time(stock.getUpdatedAt()));
            facts.add(fact);
        }
        return new ToolResult(name(), facts, page.getTotal() > facts.size(), context.asOf(), List.of());
    }
}
