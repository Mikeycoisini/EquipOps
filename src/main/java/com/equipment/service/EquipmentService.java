package com.equipment.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.equipops.domain.dto.equipment.EquipmentUpsertDto;
import com.equipops.domain.entity.EquipmentEntity;
import com.equipops.domain.vo.EquipmentVO;
import com.equipops.domain.enums.EquipmentStatus;

public interface EquipmentService extends IService<EquipmentEntity> {
    EquipmentEntity addEquipment(EquipmentEntity equipment);

    EquipmentEntity deleteEquipment(Long id);

    EquipmentEntity updateEquipment(EquipmentEntity equipment, EquipmentUpsertDto request);

    IPage<EquipmentEntity> getEquipmentPage(
            long current,
            long size,
            String keyword,
            EquipmentStatus status);


    EquipmentEntity getEquipStatusById(Long id, EquipmentStatus targetStatus);
    /** 查询当前企业的设备详情；其他企业或未归属设备返回404。 */
    EquipmentVO getEquipmentDetail(Long id);
}
