package com.inventory.service.impl;

import com.auth.service.CurrentAccessService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.equipops.domain.dto.inventory.InventoryTransactionPageQueryDto;
import com.equipops.domain.entity.SparePartEntity;
import com.equipops.domain.entity.WarehouseEntity;
import com.equipops.domain.entity.WorkOrderEntity;
import com.equipops.domain.vo.InventoryTransactionVO;
import com.inventory.mapper.InventoryTransactionMapper;
import com.inventory.mapper.SparePartMapper;
import com.inventory.service.InventoryTransactionService;
import com.tenant.mapper.WarehouseMapper;
import com.workorder.mapper.WorkOrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 查询库存历史，不修改库存余额或推测旧流水的仓库。
 */
@Service
@RequiredArgsConstructor
public class InventoryTransactionServiceImpl implements InventoryTransactionService {
    /**
     * 获取并验证可信当前企业。
     */
    private final CurrentAccessService currentAccessService;
    /**
     * 查询库存流水。
     */
    private final InventoryTransactionMapper transactionMapper;
    /**
     * 校验筛选仓库的归属。
     */
    private final WarehouseMapper warehouseMapper;
    /**
     * 校验筛选备件的归属。
     */
    private final SparePartMapper sparePartMapper;
    /**
     * 校验筛选工单的归属。
     */
    private final WorkOrderMapper workOrderMapper;

    /**
     * 先校验筛选对象，再按企业限定流水，防止通过关联 ID 绕过范围。
     *
     */
    @Override
    public IPage<InventoryTransactionVO> getTransactionPage(InventoryTransactionPageQueryDto query) {
        // 1. 验证登录用户在当前企业的成员资格，取得查询范围。
        Long tenantId = currentAccessService.requireActiveTenantId();
        // 2. 起止时间都提供时必须先后合理；只有一端时表示开放区间的单侧筛选。
        if (query.getStartTime() != null && query.getEndTime() != null
                && query.getStartTime().isAfter(query.getEndTime())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "开始时间不能晚于结束时间");
        }
        // 3. 三个关联 ID 都可省略，提供时必须是正数。
        for (Long id : new Long[]{
                query.getWarehouseId(), query.getSparePartId(), query.getWorkOrderId()}) {
            if (id != null && id <= 0)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "筛选 ID 必须大于0");
        }
        // 4. 逐个检查提供的关联对象是否属于当前企业。
        // selectCount 为0表示当前企业范围内找不到，不能据此区分不存在还是其他企业数据。
        if (query.getWarehouseId() != null && warehouseMapper.selectCount(
                new LambdaQueryWrapper<WarehouseEntity>()
                        .eq(WarehouseEntity::getTenantId, tenantId)
                        .eq(WarehouseEntity::getId, query.getWarehouseId())) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "仓库不存在");
        }
        // 提供备件条件时，同样必须验证备件归属，不能只验证流水表。
        if (query.getSparePartId() != null && sparePartMapper.selectCount(
                new LambdaQueryWrapper<SparePartEntity>()
                        .eq(SparePartEntity::getTenantId, tenantId)
                        .eq(SparePartEntity::getId, query.getSparePartId())) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "备件不存在");
        }
        // 提供工单条件时，先检查该工单在当前企业内可见。
        if (query.getWorkOrderId() != null && workOrderMapper.selectCount(
                new LambdaQueryWrapper<WorkOrderEntity>()
                        .eq(WorkOrderEntity::getTenantId, tenantId)
                        .eq(WorkOrderEntity::getId, query.getWorkOrderId())) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "工单不存在");
        }
        //5.改造为调用自定义 XML 联表查询,防呆设计（防恶意请求）
        long currentPage = Math.max(1, query.getCurrent());//保证页码最小是 1。
        long pageSize = Math.max(1, Math.min(100, query.getSize()));
        Page<InventoryTransactionVO> page = new Page<>(currentPage, pageSize);

        //  调用 Mapper 层新增的联表查询方法，直接返回 VO 分页对象
        return transactionMapper.selectPageWithDetails(page, tenantId, query);
    }

    /** 查询本企业库存流水详情，保留无关联资料或关联资源已停用的历史流水。 */
    @Override
    public InventoryTransactionVO getTransactionDetail(Long id) {
        // 1. 从登录上下文验证当前企业及成员关系，不接受客户端指定企业。
        Long tenantId = currentAccessService.requireActiveTenantId();
        // 2. 流水 ID 必须为正数；先判断空值，避免自动拆箱时出现空指针。
        if (id == null || id <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "流水 ID 必须大于0");
        }
        // 3. 同时限定企业和流水 ID，并联查同企业仓库、备件及工单资料。
        InventoryTransactionVO result = transactionMapper.selectDetailWithDetails(tenantId, id);
        // 4. 不区分记录不存在和属于其他企业，避免泄露其他企业流水信息。
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "库存流水不存在");
        }
        // 5. 只读返回，issueTransactionId 保留原领料关联，不扣增库存。
        return result;
    }
}
