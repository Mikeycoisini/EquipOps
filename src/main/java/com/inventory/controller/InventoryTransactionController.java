package com.inventory.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.common.response.Result;
import com.equipops.domain.dto.inventory.InventoryTransactionPageQueryDto;
import com.equipops.domain.vo.InventoryTransactionVO;
import com.inventory.service.InventoryTransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 库存流水查询入口，沿用现有库存类型枚举。 */
@RestController
@RequestMapping("/api/inventory/transactions")
@RequiredArgsConstructor
public class InventoryTransactionController {
    /** 库存流水只读业务。 */
    private final InventoryTransactionService inventoryTransactionService;

    /** 按仓库、备件、工单、类型和时间分页查询当前企业流水。 */
    @GetMapping
    public Result<IPage<InventoryTransactionVO>> page(
            @Valid @ModelAttribute InventoryTransactionPageQueryDto query) {
        // URL 查询参数绑定到 query，@Valid 检查参数；Service 负责企业范围及分页。
        return Result.success(inventoryTransactionService.getTransactionPage(query));
    }
    

    /** 查询当前企业的一条库存流水详情，ID 来自 URL 路径。 */
    @GetMapping("/{id}")
    public Result<InventoryTransactionVO> detail(@PathVariable("id") Long id) {
        // 不接收企业 ID；Service 必须同时校验流水 ID 与当前企业范围。
        return Result.success(inventoryTransactionService.getTransactionDetail(id));
    }
}
