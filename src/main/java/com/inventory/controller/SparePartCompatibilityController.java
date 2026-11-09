package com.inventory.controller;

import com.common.response.Result;
import com.equipment.service.EquipmentSparePartService;
import com.equipops.domain.vo.EquipmentSparePartVO;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 从备件查看当前企业的适配设备，路径ID为备件主键。 */
@RestController
@RequestMapping("/api/spare-parts/{sparePartId}/compatible-equipment")
@RequiredArgsConstructor
public class SparePartCompatibilityController {
    private final EquipmentSparePartService equipmentSparePartService;

    @GetMapping
    public Result<List<EquipmentSparePartVO>> list(@PathVariable("sparePartId") Long sparePartId) {
        return Result.success(equipmentSparePartService.listRelationsBySparePart(sparePartId));
    }
}
