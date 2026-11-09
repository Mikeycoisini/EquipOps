package com.workspace.service.impl;

import com.auth.service.CurrentAccessService;
import com.equipops.domain.vo.WorkspaceSummaryVO;
import com.inventory.mapper.WarehouseStockMapper;
import com.workorder.mapper.WorkOrderMapper;
import com.workspace.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 工作台汇总服务，只查询当前企业，不按前端当前页记录计算数量。 */
@Service
@RequiredArgsConstructor
public class WorkspaceServiceImpl implements WorkspaceService {

    //验证当前用户和当前企业的有效成员关系。
    private final CurrentAccessService currentAccessService;
    //工单统计 SQL 使用现有工单 Mapper，避免逐个状态发送多次查询。
    private final WorkOrderMapper workOrderMapper;
    //查询当前企业的仓库低库存数量。
    private final WarehouseStockMapper warehouseStockMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkspaceSummaryVO getSummary() {
        // 1. 从认证上下文获得企业 ID；未登录或企业成员关系失效时直接结束。
        Long tenantId = currentAccessService.requireActiveTenantId();

        // 2. XML 使用 COUNT 和条件求和一次统计；没有工单时各项数量为 0。
        WorkspaceSummaryVO result = workOrderMapper.selectWorkspaceSummary(tenantId);
        result.setTenantId(tenantId);
        // 3. 统计当前企业已建账余额中的低库存记录数。
        long lowStockCount = warehouseStockMapper.countLowStock(tenantId);

        // 4. 将真实统计结果写入工作台返回对象。
        result.setLowStockCount(lowStockCount);
        result.setLowStockAvailable(true);
        result.setLowStockUnavailableReason(null);
        return result;
    }
}
