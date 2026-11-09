package com.statistics.controller;

import com.common.response.Result;
import com.equipops.domain.dto.statistics.EquipmentRepairCountQueryDto;
import com.equipops.domain.dto.statistics.EquipmentProcessingDurationQueryDto;
import com.equipops.domain.vo.EquipmentRepairCountVO;
import com.equipops.domain.vo.EquipmentProcessingDurationVO;
import com.statistics.service.EquipmentStatisticsService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 当前企业设备的只读统计入口。 */
@RestController
@RequestMapping("/api/statistics/equipment")
@RequiredArgsConstructor
public class EquipmentStatisticsController {
    private final EquipmentStatisticsService equipmentStatisticsService;

    /** 按创建工单数统计设备报修频次，所有状态均计入。 */
    @GetMapping("/repair-counts")
    public Result<List<EquipmentRepairCountVO>> repairCounts(@Valid @ModelAttribute EquipmentRepairCountQueryDto query) {
        return Result.success(equipmentStatisticsService.getRepairCounts(query));
    }
    /** 按验收完成日筛选，统计创建到验收完成的平均总处理时长。 */
    @GetMapping("/processing-duration")
    public Result<List<EquipmentProcessingDurationVO>> processingDuration(@Valid @ModelAttribute EquipmentProcessingDurationQueryDto query) {
        return Result.success(equipmentStatisticsService.getProcessingDuration(query));
    }

}
