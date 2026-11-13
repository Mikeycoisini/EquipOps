package com.equipment.controller;

import com.common.response.Result;
import com.equipment.service.impl.EquipmentRuntimeMaintenanceService;
import com.equipops.domain.dto.equipment.EquipmentRuntimeMaintenanceDto;
import com.equipops.domain.vo.EquipmentRuntimeMaintenanceVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/equipment/{equipmentId}/runtime-maintenance")
@RequiredArgsConstructor
public class EquipmentRuntimeMaintenanceController {
    private final EquipmentRuntimeMaintenanceService service;

    @GetMapping
    public Result<EquipmentRuntimeMaintenanceVO> current(@PathVariable("equipmentId") Long equipmentId) {
        return Result.success(service.current(equipmentId));
    }

    @PutMapping
    public Result<EquipmentRuntimeMaintenanceVO> configure(@PathVariable("equipmentId") Long equipmentId,
            @Valid @RequestBody EquipmentRuntimeMaintenanceDto request) {
        try {
            return Result.success(service.configure(equipmentId, request.thresholdHours(), request.expectedVersion()));
        } catch (PessimisticLockingFailureException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "并发处理冲突，请稍后重试");
        }
    }
}
