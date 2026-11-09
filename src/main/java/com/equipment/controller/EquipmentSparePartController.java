package com.equipment.controller;

import java.util.List;
import com.common.response.Result;
import com.equipment.service.EquipmentSparePartService;
import com.equipops.domain.dto.equipment.EquipmentSparePartCreateDto;
import com.equipops.domain.vo.EquipmentSparePartVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 设备适用备件关系维护入口。 */
@RestController
@RequestMapping("/api/equipment/{equipmentId}/compatible-spare-parts")
@RequiredArgsConstructor
public class EquipmentSparePartController {
    /** 设备与备件适配业务。 */
    private final EquipmentSparePartService equipmentSparePartService;

    /** 为当前企业设备关联一种启用备件，重复关联返回409。 */
    @PostMapping
    public Result<EquipmentSparePartVO> create(@PathVariable("equipmentId") Long equipmentId,
            @Valid @RequestBody EquipmentSparePartCreateDto request) {
        return Result.success(equipmentSparePartService.createRelation(equipmentId, request));
    }
    /** 查询当前企业设备的全部适配备件，保留停用备件。 */
    @GetMapping
    public Result<List<EquipmentSparePartVO>> list(@PathVariable("equipmentId") Long equipmentId) {
        return Result.success(equipmentSparePartService.listRelations(equipmentId));
    }

    /** 解除设备与备件的适配关系。 */
    @DeleteMapping("/{sparePartId}")
    public Result<Void> remove(@PathVariable("equipmentId") Long equipmentId,
            @PathVariable("sparePartId") Long sparePartId) {
        equipmentSparePartService.removeRelation(equipmentId, sparePartId);
        return Result.success();
    }

}
