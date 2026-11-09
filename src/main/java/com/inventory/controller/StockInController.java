package com.inventory.controller;

import com.common.response.Result;
import com.equipops.domain.dto.inventory.StockInCreateDto;
import com.equipops.domain.vo.InventoryTransactionVO;
import com.inventory.service.StockInService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 备件入库接口，接收请求并交给业务层处理。 */
@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class StockInController {

    private final StockInService stockInService;

    /** 向当前企业的指定仓库入库，重复请求不能重复增加库存。 */
    @PostMapping("/stock-ins")
    public Result<InventoryTransactionVO> stockIn(
            @RequestHeader("Idempotency-Key") String requestId,
            @Valid @RequestBody StockInCreateDto request) {
            InventoryTransactionVO inventoryTransactionVO = stockInService.stocktoIn(requestId, request);
        return Result.success(inventoryTransactionVO);
    }
}