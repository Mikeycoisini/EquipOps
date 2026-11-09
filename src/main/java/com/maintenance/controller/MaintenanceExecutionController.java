package com.maintenance.controller;

import com.common.response.Result;
import com.equipops.domain.vo.MaintenanceExecutionVO;
import com.maintenance.service.MaintenancePlanServer;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 单条执行记录查询入口，返回登记时保存的历史资料。 */
@RestController
@RequestMapping("/api/maintenance-executions")
@RequiredArgsConstructor
public class MaintenanceExecutionController {
    /** 复用当前维护模块业务服务。 */
    private final MaintenancePlanServer maintenancePlanServer;

    /** 路径id是执行记录主键，不是计划主键。 */
    @GetMapping("/{id}")
    public Result<MaintenanceExecutionVO> detail(@PathVariable("id") Long id) {
        return Result.success(maintenancePlanServer.getExecutionDetail(id));
    }
}
