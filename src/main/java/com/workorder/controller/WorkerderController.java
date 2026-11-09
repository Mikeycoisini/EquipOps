package com.workorder.controller;

import com.common.response.Result;
import com.equipops.domain.dto.workorder.*;
import com.equipops.domain.vo.WorkOrderVO;
import com.equipops.domain.vo.WorkOrderLogVO;
import com.equipops.domain.vo.WorkOrderPartVO;
import com.baomidou.mybatisplus.core.metadata.IPage;
import java.util.List;
import com.workorder.service.WorkOrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/work-orders")
@RequiredArgsConstructor
public class WorkerderController {

    private final WorkOrderService workOrderService;

    /**
     * 报修并创建维修工单。
     */
    @PostMapping
    public Result<WorkOrderVO> reportRepair(
            @Valid @RequestBody WorkOrderCreateDto request) {
        WorkOrderVO workOrderVO = workOrderService.reportRepair(request);
        return Result.success(workOrderVO);
    }

    /**
     * 将维修工单派发给指定负责人。
     */
    @PutMapping("/{id}/dispatch")
    public Result<WorkOrderVO> dispatch(
            @PathVariable Long id,
            @Valid @RequestBody WorkOrderDispatchDto request) {
        return Result.success(workOrderService.dispatch(id, request));
    }

    /** 分页查询当前企业的维修工单。 */
    @GetMapping
    public Result<IPage<WorkOrderVO>> page(@Valid @ModelAttribute WorkOrderPageQueryDto query) {
        // URL 查询参数绑定到 query，@Valid 检查参数；Service 负责企业范围及分页。
        return Result.success(workOrderService.getWorkOrderPage(query));
    }

    /** 查询当前企业工单详情，包含关联设备、用料和处理时间线。 */
    @GetMapping("/{id}")
    public Result<WorkOrderVO> detail(@PathVariable("id") Long id) {
        // 路径只提供工单 ID，企业范围由 Service 从登录状态取得。
        return Result.success(workOrderService.getWorkOrderDetail(id));
    }

    /** 查询当前企业指定工单的用料汇总，未发生用料时返回空列表。 */
    @GetMapping("/{id}/parts")
    public Result<List<WorkOrderPartVO>> parts(@PathVariable("id") Long id) {
        // Service 先校验工单归属，再查询备件编号、名称及领用/退回数量。
        return Result.success(workOrderService.getWorkOrderParts(id));
    }

    /** 查询当前企业指定工单的日志；其他企业的工单按不存在处理。 */
    @GetMapping("/{id}/logs")
    public Result<List<WorkOrderLogVO>> logs(@PathVariable("id") Long id) {
        // id 来自 URL 路径；Service 必须确认对象属于当前企业，再返回资料。
        return Result.success(workOrderService.getWorkOrderLogs(id));
    }
    /** 当前负责人开始维修指定工单。 */
    @PostMapping("/{id}/start")
    public Result<WorkOrderVO> startRepairing(@PathVariable("id") Long id) {
        return Result.success(workOrderService.startRepair(id));
    }
    /** 当前负责人追加维修处理记录。 */
    @PostMapping("/{id}/repair-notes")
    public Result<WorkOrderLogVO> addRepairNoteLog(
            @PathVariable("id") Long id,
            @Valid @RequestBody RepairNoteCreateDto request) {

        return Result.success(workOrderService.addRepairNoteLog(id, request));
    }

    /** 当前负责人提交维修工单，等待验收。 */
    @PostMapping("/{id}/submit")
    public Result<WorkOrderVO> submitAcceptance(@PathVariable("id") Long id) {
        return Result.success(workOrderService.submitAcceptance(id));
    }

    /** 验收通过，将待验收工单标记为已完成。 */
    @PostMapping("/{id}/approve")
    public Result<WorkOrderVO> approve(@PathVariable("id") Long id) {
        WorkOrderVO Vo=workOrderService.approve(id);
        return Result.success(Vo);
    }
    /** 验收不通过，填写原因后退回原负责人继续维修。 */
    @PostMapping("/{id}/reject")
    public Result<WorkOrderVO> reject(
            @PathVariable("id") Long id,
            @Valid @RequestBody WorkOrderRejectDto request) {
        return Result.success(workOrderService.reject(id, request));
    }
}
