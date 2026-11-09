package com.equipment.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.common.response.Result;
import com.equipment.service.EquipmentService;
import com.equipops.domain.dto.equipment.EquipmentUpsertDto;
import com.equipops.domain.entity.EquipmentEntity;
import com.equipops.domain.enums.EquipmentStatus;
import com.equipops.domain.vo.EquipmentVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/equipment")
@Slf4j
@RequiredArgsConstructor
public class EquipmentController {
    private final EquipmentService equipmentService;

    /** 添加设备档案。 */
    @PostMapping
    public Result<EquipmentEntity> add(@Valid @RequestBody EquipmentEntity equipment) {
        EquipmentEntity equipmentEntity = equipmentService.addEquipment(equipment);
        return Result.success(equipmentEntity);
    }
    /** 修改设备 */
    @PutMapping("/{id}")
    public Result<EquipmentEntity> update(
            @PathVariable("id") Long id,
            @Valid @RequestBody EquipmentUpsertDto request) {
        EquipmentEntity equipment = new EquipmentEntity();
        equipment.setId(id);
        EquipmentEntity equipmentEntity = equipmentService.updateEquipment(equipment, request);
        return Result.success(equipmentEntity);
    }
    /** 删除设备*/
    @DeleteMapping("/{id}")
    public Result<EquipmentEntity> deleteEquipment(@PathVariable Long id) {
        EquipmentEntity equipmentEntity = equipmentService.deleteEquipment(id);
        return Result.success(equipmentEntity);
    }
    /** 查询设备列表。 */
    @GetMapping
    public Result<IPage<EquipmentVO>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) EquipmentStatus status) {

        IPage<EquipmentEntity> entityPage =
                equipmentService.getEquipmentPage(current, size, keyword,status);

        return Result.success(entityPage.convert(this::toVO));
    }
    /** 查询设备状态 */
    @PutMapping("/{id}/status")
    public Result<EquipmentEntity> changeStatus(
            @PathVariable Long id,
            @RequestParam EquipmentStatus targetStatus) {
        EquipmentEntity result = equipmentService.getEquipStatusById(id, targetStatus);
        return Result.success(result);
    }

    /** 查询当前企业指定设备的数字档案,设备详情。 */
    @GetMapping("/{id}")
    public Result<EquipmentVO> detail(@PathVariable("id") Long id) {
        // id 来自 URL 路径；Service 必须确认对象属于当前企业，再返回资料。
        return Result.success(equipmentService.getEquipmentDetail(id));
    }

    private EquipmentVO toVO(EquipmentEntity equipment) {
        EquipmentVO vo = new EquipmentVO();
        BeanUtils.copyProperties(equipment, vo);
        return vo;
    }

}
