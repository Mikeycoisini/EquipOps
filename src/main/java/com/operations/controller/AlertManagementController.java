package com.operations.controller;
import com.common.response.Result;
import com.equipops.domain.dto.operations.*;
import com.equipops.domain.entity.AlertRuleEntity;
import com.equipops.domain.enums.AlertActionType;
import com.equipops.domain.vo.*;
import com.operations.service.impl.AlertManagementServiceImpl;
import com.baomidou.mybatisplus.core.metadata.IPage;
import java.util.List;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/operations")
@RequiredArgsConstructor
public class AlertManagementController {
    // 注入规则和处置服务。
    private final AlertManagementServiceImpl service;
    // 获取当前企业固定规则。
    @GetMapping("/alert-rules/export-failures")
    public Result<AlertRuleEntity> rule() {
        return Result.success(service.getRule());
    }
    // 创建或条件更新规则。
    @PutMapping("/alert-rules/export-failures")
    public Result<AlertRuleEntity> save(@Valid @RequestBody AlertRuleSaveDto request) {
        return Result.success(service.saveRule(request));
    }
    // 查询告警时间线。
    @GetMapping("/alerts/{id}/actions")
    public Result<List<AlertActionVO>> actions(@PathVariable("id") Long id) {
        return Result.success(service.actions(id));
    }
    // 确认接手。
    @PostMapping("/alerts/{id}/acknowledge")
    public Result<AlertEventVO> acknowledge(@PathVariable("id") Long id,@Valid @RequestBody AlertActionDto request) {
        return Result.success(service.act(id,request,AlertActionType.ACKNOWLEDGE));
    }
    // 添加处置备注。
    @PostMapping("/alerts/{id}/notes")
    public Result<AlertEventVO> note(@PathVariable("id") Long id,@Valid @RequestBody AlertActionDto request) {
        return Result.success(service.act(id,request,AlertActionType.ADD_NOTE));
    }
    // 人工结案，保留原检测恢复状态。
    @PostMapping("/alerts/{id}/resolve")
    public Result<AlertEventVO> resolve(@PathVariable("id") Long id,@Valid @RequestBody AlertActionDto request) {
        return Result.success(service.act(id,request,AlertActionType.RESOLVE));
    }
    // 获取当前企业本人的站内提醒。
    @GetMapping("/notifications")
    public Result<IPage<AlertNotificationVO>> notifications(@RequestParam(defaultValue="1") long current,@RequestParam(defaultValue="10") long size) {
        return Result.success(service.notifications(current,size));
    }
    // 幂等标记已读。
    @PostMapping("/notifications/{id}/read")
    public Result<Void> read(@PathVariable("id") Long id) {
        service.readNotification(id);
        return Result.success();
    }
}
