package com.equipment.controller;

import com.common.response.Result;
import com.equipment.service.impl.EquipmentRuntimeReadingService;
import com.equipops.domain.dto.equipment.EquipmentRuntimeReadingDto;
import com.equipops.domain.vo.EquipmentRuntimeReadingVO;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/equipment/{equipmentId}/runtime-readings")
@RequiredArgsConstructor
public class EquipmentRuntimeReadingController {
    private final EquipmentRuntimeReadingService service;

    @GetMapping
    public Result<List<EquipmentRuntimeReadingVO>> recent(@PathVariable("equipmentId") Long equipmentId) {
        return Result.success(service.recent(equipmentId));
    }

    @PostMapping
    public Result<EquipmentRuntimeReadingVO> record(@PathVariable("equipmentId") Long equipmentId,
            @Valid @RequestBody EquipmentRuntimeReadingDto request) {
        try {
            return Result.success(service.record(equipmentId, request.hours()));
        } catch (PessimisticLockingFailureException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "并发处理冲突，请稍后重试");
        }
    }
}
