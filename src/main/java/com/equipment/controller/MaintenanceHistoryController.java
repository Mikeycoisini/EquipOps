package com.equipment.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.common.response.Result;
import com.equipment.service.MaintenanceHistoryService;
import com.equipops.domain.vo.WorkOrderVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/equipment")
@Slf4j
public class MaintenanceHistoryController {
    @Autowired
    MaintenanceHistoryService maintenanceHistoryService;
    /**
     * 分页查询指定设备的维修履历。
     */
    @GetMapping("/history")
    public Result<IPage<WorkOrderVO>> history(
            @RequestParam Long equipmentId,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size) {
        IPage<WorkOrderVO> historyPage = maintenanceHistoryService.getMaintenanceHistory(equipmentId, current, size);
        return Result.success(historyPage);
    }
}
