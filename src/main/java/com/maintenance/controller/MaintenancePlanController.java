package com.maintenance.controller;

import com.common.response.Result;
import com.equipops.domain.dto.maintenance.MaintenanceExecutionPageQueryDto;
import com.equipops.domain.dto.maintenance.MaintenanceExecutionCreateDto;
import com.equipops.domain.vo.MaintenanceExecutionVO;
import com.equipops.domain.dto.maintenance.MaintenancePlanUpdateDto;
import com.equipops.domain.dto.maintenance.MaintenancePlanEnabledUpdateDto;
import com.equipops.domain.dto.maintenance.MaintenancePlanPageQueryDto;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.equipops.domain.dto.maintenance.MaintenancePlanCreateDto;
import com.equipops.domain.vo.MaintenancePlanVO;
import com.maintenance.service.MaintenancePlanServer;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 维护与巡检计划接口，负责接收请求并交给业务层处理。 */
@RestController
@RequestMapping("/api/maintenance-plans")
@RequiredArgsConstructor
@Slf4j
public class MaintenancePlanController {

    /** 计划业务入口；身份、权限和设备归属由业务层校验。 */
    private final MaintenancePlanServer maintenancePlanServer;

    /** 创建日期周期计划；请求字段先按DTO约束校验，通过后调用业务层。 */
    @PostMapping
    public Result<MaintenancePlanVO> createPlan(
            @Valid @RequestBody MaintenancePlanCreateDto request) {
        MaintenancePlanVO plan = maintenancePlanServer.createPlan(request);
        return Result.success(plan);
    }
    /** 查询当前企业的维护计划；筛选条件来自URL查询参数，不使用请求体。 */
    @GetMapping
    public Result<IPage<MaintenancePlanVO>> page(@Valid @ModelAttribute MaintenancePlanPageQueryDto query) {
        return Result.success(maintenancePlanServer.getPlanPage(query));
    }

    /** 查看当前企业的指定计划；ID从URL路径读取。 */
    @GetMapping("/{id}")
    public Result<MaintenancePlanVO> detail(@PathVariable("id") Long id) {
        return Result.success(maintenancePlanServer.getPlanDetail(id));
    }

    /** 管理员启用或停用计划；重复设置相同状态也视为成功。 */
    @PatchMapping("/{id}/enabled")
    public Result<Void> updateEnabled(@PathVariable("id") Long id,
                                      @Valid @RequestBody MaintenancePlanEnabledUpdateDto request) {
        maintenancePlanServer.updatePlanEnabled(id, request);
        return Result.success();
    }

    /** 管理员完整修改计划的可编辑字段，返回更新后的详情。 */
    @PutMapping("/{id}")
    public Result<MaintenancePlanVO> update(@PathVariable("id") Long id,
                                            @Valid @RequestBody MaintenancePlanUpdateDto request) {
        return Result.success(maintenancePlanServer.updatePlan(id, request));
    }

    /** 负责人登记已到期计划的执行结果，相同请求号重试不会重复推进周期。 */
    @PostMapping("/{id}/executions")
    public Result<MaintenanceExecutionVO> createExecution(@PathVariable("id") Long id,
            @RequestHeader("Idempotency-Key") String requestId,
            @Valid @RequestBody MaintenanceExecutionCreateDto request) {
        return Result.success(maintenancePlanServer.createExecution(id, requestId, request));
    }

    /** 当前企业有效成员查看计划执行历史，停用计划仍可查询。 */
    @GetMapping("/{id}/executions")
    public Result<IPage<MaintenanceExecutionVO>> executionPage(@PathVariable("id") Long id,
            @Valid @ModelAttribute MaintenanceExecutionPageQueryDto query) {
        return Result.success(maintenancePlanServer.getExecutionPage(id, query));
    }

}
