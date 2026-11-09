package com.workorder.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.common.response.Result;
import com.equipops.domain.vo.WorkOrderVO;
import com.equipops.domain.vo.WorkspaceSummaryVO;
import com.workorder.service.EquipmentWorkOrderServer;
import com.workspace.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 设备档案中的关联工单查询入口，调用独立设备工单业务。 */
@RestController
@RequestMapping("/api/equipment/{equipmentId}/work-orders")
@RequiredArgsConstructor
public class EquipmentWorkOrderController {

    /** 设备关联工单业务，负责企业校验、分页和返回对象转换。 */
    private final EquipmentWorkOrderServer equipmentWorkOrderServer;

    /** 查询当前企业指定设备的工单；没有匹配工单时返回空分页。 */
    @GetMapping
    public Result<IPage<WorkOrderVO>> page(
            @PathVariable("equipmentId") Long equipmentId,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size) {
        // 路径设备 ID 与分页参数交给独立业务层处理。
        return Result.success(equipmentWorkOrderServer.getWorkOrderPage(equipmentId, current, size));
    }

}