package com.ai.tool;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.ai.tool.data.SopSearchRepository;
import com.ai.tool.impl.*;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.equipment.service.EquipmentService;
import com.equipment.service.EquipmentSparePartService;
import com.equipment.service.MaintenanceHistoryService;
import com.equipops.domain.dto.inventory.WarehouseStockPageQueryDto;
import com.equipops.domain.dto.operations.AlertPageQueryDto;
import com.equipops.domain.enums.AlertEventStatus;
import com.equipops.domain.enums.EquipmentStatus;
import com.equipops.domain.vo.*;
import com.inventory.service.WarehouseStockService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 六工具白名单、空数据、截断与数量格式。 */
class ToolImplementationsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");
    private static final ToolContext CONTEXT = new ToolContext(1, 2, 9, "test", NOW);

    @Test
    void equipmentInfoHidesPrivateFields() throws Exception {
        EquipmentService service = mock(EquipmentService.class);
        EquipmentVO value = new EquipmentVO();
        value.setId(12L);
        value.setTenantId(1L);
        value.setSiteId(3L);
        value.setName("空压机");
        value.setStatus(EquipmentStatus.RUNNING);
        value.setManagerUserId(9L);
        when(service.getEquipmentDetail(12L)).thenReturn(value);
        ToolResult result = new GetEquipmentInfoTool(service).execute(CONTEXT, args("{\"equipmentId\":12}"));
        assertEquals("12", result.facts().get(0).get("equipmentId"));
        assertFalse(result.facts().get(0).containsKey("tenantId"));
        assertFalse(result.facts().get(0).containsKey("managerUserId"));
    }

    @Test
    void emptyHistoryIsEmptyFacts() throws Exception {
        MaintenanceHistoryService service = mock(MaintenanceHistoryService.class);
        Page<WorkOrderVO> page = new Page<>(1, 20);
        page.setRecords(List.of());
        page.setTotal(0);
        when(service.getMaintenanceHistory(12L, 1, 20)).thenReturn(page);
        ToolResult result = new GetEquipmentHistoryTool(service).execute(CONTEXT, args("{\"equipmentId\":12}"));
        assertTrue(result.facts().isEmpty());
        assertFalse(result.truncated());
    }

    @Test
    void sparePartsSkipDisabled() throws Exception {
        EquipmentSparePartService service = mock(EquipmentSparePartService.class);
        EquipmentSparePartVO enabled = new EquipmentSparePartVO();
        enabled.setId(1L);
        enabled.setEquipmentId(12L);
        enabled.setSparePartId(10L);
        enabled.setSparePartEnabled(true);
        EquipmentSparePartVO disabled = new EquipmentSparePartVO();
        disabled.setId(2L);
        disabled.setEquipmentId(12L);
        disabled.setSparePartId(11L);
        disabled.setSparePartEnabled(false);
        when(service.listRelations(12L)).thenReturn(List.of(enabled, disabled));
        ToolResult result = new SearchSparePartsTool(service).execute(CONTEXT, args("{\"equipmentId\":12}"));
        assertEquals(1, result.facts().size());
        assertEquals("10", result.facts().get(0).get("sparePartId"));
        assertTrue(result.truncated());
    }

    @Test
    void stockQuantityAlwaysThreeDecimals() throws Exception {
        WarehouseStockService service = mock(WarehouseStockService.class);
        WarehouseStockVO stock = new WarehouseStockVO();
        stock.setWarehouseId(3L);
        stock.setSparePartId(10L);
        stock.setQuantity(new BigDecimal("0"));
        Page<WarehouseStockVO> page = new Page<>(1, 20);
        page.setRecords(List.of(stock));
        page.setTotal(1);
        when(service.getStockPage(any(WarehouseStockPageQueryDto.class))).thenReturn(page);
        ToolResult result = new GetInventoryStockTool(service).execute(CONTEXT, args("{\"sparePartId\":10}"));
        assertEquals("0.000", result.facts().get(0).get("quantity"));
    }

    @Test
    void alarmsMergeAndMarkTruncated() throws Exception {
        com.operations.service.AlertQueryService service = mock(com.operations.service.AlertQueryService.class);
        when(service.page(any(AlertPageQueryDto.class))).thenAnswer(invocation -> {
            AlertPageQueryDto query = invocation.getArgument(0);
            Page<AlertEventVO> page = new Page<>(1, 20);
            AlertEventVO alarm = new AlertEventVO();
            alarm.setId(query.getStatus() == AlertEventStatus.OPEN ? 1L : 2L);
            alarm.setStatus(query.getStatus());
            alarm.setOwnerUserId(9L);
            alarm.setLastSeenAt(LocalDateTime.now());
            page.setRecords(List.of(alarm));
            page.setTotal(query.getStatus() == AlertEventStatus.OPEN ? 50 : 1);
            return page;
        });
        ToolResult result = new GetActiveAlarmsTool(service).execute(CONTEXT, args("{}"));
        assertEquals(2, result.facts().size());
        assertTrue(result.truncated());
        assertFalse(result.facts().get(0).containsKey("ownerUserId"));
    }

    @Test
    void sopReturnsChunkIdsAndSnippet() throws Exception {
        SopSearchRepository repository = mock(SopSearchRepository.class);
        when(repository.search(1L, "轴承", 5)).thenReturn(List.of(
                new SopSearchRepository.SopChunk(1, 2, 3, "手册", "第三章", "轴承更换步骤")));
        ToolResult result = new SearchMaintenanceSopTool(repository).execute(CONTEXT, args("{\"query\":\"轴承\"}"));
        assertEquals("1", result.facts().get(0).get("chunkId"));
        assertFalse(result.facts().get(0).containsKey("contentText"));
    }

    private JsonNode args(String value) throws Exception {
        return JSON.readTree(value);
    }
}
