package com.inventory.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.common.response.Result;
import com.equipops.domain.dto.inventory.WarehouseStockPageQueryDto;
import com.equipops.domain.dto.inventory.WarehouseStockMinQuantityUpdateDto;
import com.equipops.domain.vo.WarehouseStockVO;
import com.inventory.service.WarehouseStockService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 仓库库存查询和安全库存维护入口；继续使用 Session 登录和当前企业上下文。 */
@RestController
@RequestMapping("/api/warehouse-stocks")
@RequiredArgsConstructor
public class WarehouseStockController {
    /** 仓库库存业务服务，Controller 仅负责请求与响应。 */
    private final WarehouseStockService warehouseStockService;

    /** 按仓库、备件、关键字与低库存条件分页查询余额。 */
    @GetMapping
    public Result<IPage<WarehouseStockVO>> page(@Valid @ModelAttribute WarehouseStockPageQueryDto query) {
        // 不接收 tenantId；Service 从登录状态确定企业，Result 保留分页结构。
        return Result.success(warehouseStockService.getStockPage(query));
    }

    /** 修改一条仓库余额的安全库存阈值，业务层负责企业管理员权限校验。 */
    @PatchMapping("/{id}/min-stock-quantity")
    public Result<WarehouseStockVO> updateMinStockQuantity(
            @PathVariable("id") Long id,
            @Valid @RequestBody WarehouseStockMinQuantityUpdateDto request) {
        // id 是库存余额记录ID，不是仓库ID；请求体不能修改实际库存数量。
        return Result.success(warehouseStockService.updateMinStockQuantity(id, request));
    }
}
