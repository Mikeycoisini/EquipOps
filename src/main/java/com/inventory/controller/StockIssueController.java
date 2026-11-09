package com.inventory.controller;

import com.common.response.Result;
import com.equipops.domain.dto.inventory.StockIssueCreateDto;
import com.equipops.domain.vo.InventoryTransactionVO;
import com.inventory.service.StockIssueService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 工单领料。 */
@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class StockIssueController {

    /** 工单领料业务。 */
    private final StockIssueService stockIssueService;

    /** 为当前企业工单领用备件，重复请求不得重复扣库存。 */
    @PostMapping("/issues")
    public Result<InventoryTransactionVO> issue(
            @RequestHeader("Idempotency-Key") String requestId,
            @Valid @RequestBody StockIssueCreateDto request) {
        InventoryTransactionVO result = stockIssueService.issue(requestId, request);
        return Result.success(result);
    }
}