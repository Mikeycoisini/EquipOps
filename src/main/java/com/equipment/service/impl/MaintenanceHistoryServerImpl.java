package com.equipment.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workorder.mapper.WorkOrderMapper;
import com.auth.service.CurrentAccessService;
import com.equipment.service.EquipmentService;
import com.equipment.service.MaintenanceHistoryService;
import com.equipops.domain.entity.EquipmentEntity;
import com.equipops.domain.entity.WorkOrderEntity;
import com.equipops.domain.enums.WorkOrderStatus;
import com.equipops.domain.vo.WorkOrderVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;


@Service
@RequiredArgsConstructor
public class MaintenanceHistoryServerImpl implements MaintenanceHistoryService {
    /**
     * 工单数据库操作。
     */
    private final WorkOrderMapper workOrderMapper;
    /**
     * 设备业务服务，用于检查设备是否存在。
     */
    private final EquipmentService equipmentService;
    /** 为设备履历增加当前企业范围。 */
    private final CurrentAccessService currentAccessService;

    @Override
    public IPage<WorkOrderVO> getMaintenanceHistory(Long equipmentId, long current, long size) {
        Long tenantId = currentAccessService.requireActiveTenantId();
        //检查 equipmentId 是否有效
        if (equipmentId == null) {
            throw new IllegalArgumentException("设备不能为空！");
        }
        //查询设备是否存在 不存在 → 提示设备不存在
        com.equipops.domain.vo.EquipmentVO byId = equipmentService.getEquipmentDetail(equipmentId);
        if (byId == null) {
            throw new IllegalArgumentException("设备不存在！");
        }
        //校验页码和每页数量（防前端乱传导致数据库崩溃）
        if (current < 1) {//当前页面
            current = 1;
        }
        //最小页码为1
        if (size < 1) {
            size = 1;
        } else if (size > 100) {
            size = 100;
        }
        //  校验设备是否存在
        boolean isExist = byId != null;
        if (!isExist) {
            throw new IllegalArgumentException("设备不存在");
        }
        // 3. 创建分页对象。
        Page<WorkOrderEntity> page = new Page<>(current, size);

        // 4. 设置查询条件，只查这台设备已经完成的工单。
        LambdaQueryWrapper<WorkOrderEntity> wrapper =
                new LambdaQueryWrapper<>();

        wrapper.eq(WorkOrderEntity::getTenantId, tenantId)
                .eq(WorkOrderEntity::getEquipmentId, equipmentId)
                .eq(WorkOrderEntity::getStatus, WorkOrderStatus.COMPLETED)
                .orderByDesc(WorkOrderEntity::getCompletedAt)
                .orderByDesc(WorkOrderEntity::getId);

        // 5. 查询数据库，取得记录及分页信息。
        IPage<WorkOrderEntity> entityPage =
                workOrderMapper.selectPage(page, wrapper);

        // 6. 转换为前端使用的 VO，保留分页信息。
        return entityPage.convert(entity -> {
            WorkOrderVO vo = new WorkOrderVO();
            BeanUtils.copyProperties(entity, vo);
            return vo;
        });
    }
}
