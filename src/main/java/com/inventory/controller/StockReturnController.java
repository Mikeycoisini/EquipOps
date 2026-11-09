package com.inventory.controller;

import com.common.response.Result;
import com.equipops.domain.dto.inventory.StockReturnCreateDto;
import com.equipops.domain.vo.InventoryTransactionVO;
import com.inventory.service.StockReturnService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 工单退料入口*/
@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class StockReturnController {

    /** 工单退料业务。 */
    private final StockReturnService stockReturnService;

    /** 按原领料流水退回未使用备件，退料业务完成后返回对应库存流水。 */
    @PostMapping("/returns")
    public Result<InventoryTransactionVO> returnStock(
            @RequestHeader("Idempotency-Key") String requestId,
            @Valid @RequestBody StockReturnCreateDto request) {
        InventoryTransactionVO result = stockReturnService.returnStock(requestId, request);
        return Result.success(result);
    }
}
