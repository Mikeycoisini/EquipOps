package com.ai.tool.impl;

import com.ai.tool.ReadonlyTool;
import com.ai.tool.ToolContext;
import com.ai.tool.ToolFacts;
import com.ai.tool.ToolLimits;
import com.ai.tool.ToolResult;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.equipops.domain.dto.operations.AlertPageQueryDto;
import com.equipops.domain.enums.AlertEventStatus;
import com.equipops.domain.vo.AlertEventVO;
import com.operations.service.AlertQueryService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** 查询当前企业OPEN/ACKNOWLEDGED告警；复用既有管理员服务。 */
@Component
@RequiredArgsConstructor
public class GetActiveAlarmsTool implements ReadonlyTool {
    private final AlertQueryService alerts;

    @Override
    public String name() {
        return "get_active_alarms";
    }

    @Override
    public String description() {
        return "查询当前企业OPEN或ACKNOWLEDGED告警，最多20条。仅企业管理员；现有告警无设备关联，不支持按设备过滤。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "additionalProperties", false, "properties", Map.of());
    }

    @Override
    public Set<String> factFields() {
        return Set.of("alarmId", "ruleCode", "errorCode", "status", "recoveryStatus", "occurrenceCount",
                "firstSeenAt", "lastSeenAt", "acknowledgedAt", "recoveredAt");
    }

    @Override
    public ToolResult execute(ToolContext context, JsonNode arguments) {
        IPage<AlertEventVO> openPage = query(AlertEventStatus.OPEN);
        IPage<AlertEventVO> acknowledgedPage = query(AlertEventStatus.ACKNOWLEDGED);
        List<AlertEventVO> active = new ArrayList<>();
        active.addAll(openPage.getRecords());
        active.addAll(acknowledgedPage.getRecords());
        active.sort(Comparator.comparing(AlertEventVO::getLastSeenAt, Comparator.nullsLast(Comparator.reverseOrder())));
        int count = Math.min(active.size(), ToolLimits.MAX_ROWS);
        List<Map<String, Object>> facts = new ArrayList<>();
        for (AlertEventVO alarm : active.subList(0, count)) {
            LinkedHashMap<String, Object> fact = new LinkedHashMap<>();
            ToolFacts.put(fact, "alarmId", ToolFacts.id(alarm.getId()));
            ToolFacts.put(fact, "ruleCode", alarm.getRuleCode() == null ? null : alarm.getRuleCode().name());
            ToolFacts.put(fact, "errorCode", alarm.getErrorCode());
            ToolFacts.put(fact, "status", alarm.getStatus() == null ? null : alarm.getStatus().name());
            ToolFacts.put(fact, "recoveryStatus", alarm.getRecoveryStatus() == null ? null : alarm.getRecoveryStatus().name());
            ToolFacts.put(fact, "occurrenceCount", alarm.getOccurrenceCount() == null ? null : Long.toString(alarm.getOccurrenceCount()));
            ToolFacts.put(fact, "firstSeenAt", ToolFacts.time(alarm.getFirstSeenAt()));
            ToolFacts.put(fact, "lastSeenAt", ToolFacts.time(alarm.getLastSeenAt()));
            ToolFacts.put(fact, "acknowledgedAt", ToolFacts.time(alarm.getAcknowledgedAt()));
            ToolFacts.put(fact, "recoveredAt", ToolFacts.time(alarm.getRecoveredAt()));
            facts.add(fact);
        }
        boolean truncated = openPage.getTotal() > openPage.getRecords().size()
                || acknowledgedPage.getTotal() > acknowledgedPage.getRecords().size()
                || active.size() > count;
        return new ToolResult(name(), facts, truncated, context.asOf(), List.of());
    }

    private IPage<AlertEventVO> query(AlertEventStatus status) {
        return alerts.page(new AlertPageQueryDto(1, ToolLimits.MAX_ROWS, status, null, null));
    }
}
