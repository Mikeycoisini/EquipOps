package com.workorder.service.impl;

import com.auth.service.CurrentAccessService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.equipops.domain.entity.WorkOrderEntity;
import com.equipops.domain.vo.WorkOrderVO;
import com.workorder.mapper.EquipmentWorkOrderMapper;
import com.workorder.service.EquipmentWorkOrderServer;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 设备关联工单业务，保留原列表接口的分页及返回语义。 */
@Service
@RequiredArgsConstructor
public class EquipmentWorkOrderServerImpl implements EquipmentWorkOrderServer {
    /** 从登录状态验证当前企业及有效成员关系。 */
    private final CurrentAccessService currentAccessService;
    /** 查询本企业、本设备的工单。 */
    private final EquipmentWorkOrderMapper equipmentWorkOrderMapper;

    /** 不加载工单日志和用料；没有匹配记录时返回空分页。 */
    @Override
    public IPage<WorkOrderVO> getWorkOrderPage(Long equipmentId, long current, long size) {
        // 1. 企业身份来自服务器，不能由客户端指定。
        Long tenantId = currentAccessService.requireActiveTenantId();
        // 2. 设备 ID 必须有效，避免缺少设备条件而查询全部工单。
        if (equipmentId == null || equipmentId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备 ID 必须大于0");
        }
        // 3. 沿用原分页规则：页码至少为1，每页1到100条。
        Page<WorkOrderEntity> page = new Page<>(Math.max(1, current), Math.max(1, Math.min(100, size)));
        // 4. XML 同时限制企业和设备，按创建时间、ID倒序。
        IPage<WorkOrderEntity> result = equipmentWorkOrderMapper.selectWorkOrderPage(page, tenantId, equipmentId);
        // 5. 保留分页元数据，仅将实体记录转换成 VO。
        return result.convert(entity -> {
            WorkOrderVO vo = new WorkOrderVO();
            BeanUtils.copyProperties(entity, vo);
            // 列表未加载时间线，空列表不表示工单没有日志。
            vo.setTimeline(List.of());
            return vo;
        });
    }
}