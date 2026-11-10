package com.workorder.controller;
import com.common.response.Result;
import com.equipops.domain.dto.workorder.WorkOrderReassignDto;
import com.equipops.domain.vo.WorkOrderVO;
import com.workorder.service.WorkOrderReassignService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
/** 受控转派独立入口，保留原工单Controller契约。 */
@RestController @RequiredArgsConstructor
@RequestMapping("/api/work-orders/{id}/reassign")
public class WorkOrderReassignController {
 private final WorkOrderReassignService service;
 @PostMapping public Result<WorkOrderVO> reassign(@PathVariable("id")Long id,@Valid @RequestBody WorkOrderReassignDto request){
  // 1. 交由事务与审计编排验证身份、状态、版本和资格，成功后返回完整工单。
  return Result.success(service.reassign(id,request));
 }
}
