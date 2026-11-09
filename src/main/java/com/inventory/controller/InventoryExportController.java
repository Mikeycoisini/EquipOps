package com.inventory.controller;

import com.common.response.Result;
import com.equipops.domain.dto.inventory.InventoryExportCreateDto;
import com.equipops.domain.vo.InventoryExportTaskVO;
import com.inventory.service.InventoryExportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.equipops.domain.dto.inventory.InventoryExportPageQueryDto;

/** 库存流水Excel导出入口；只接收参数和转换HTTP响应。 */
@RestController
@RequestMapping("/api/inventory/exports")
@RequiredArgsConstructor
public class InventoryExportController {
    private final InventoryExportService inventoryExportService;

    /** 提交导出筛选；202仅表示已接收任务，不表示Excel已经生成。 */
    @PostMapping
    public ResponseEntity<Result<InventoryExportTaskVO>> create(
            @Valid @RequestBody InventoryExportCreateDto request) {
        InventoryExportTaskVO task = inventoryExportService.create(request);//“服务器已接受请求，但尚未处理完成
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Result.success("导出任务已提交", task));
    }

    /** 只读分页查询本人任务；身份范围由Service从可信上下文取得。 */
    @GetMapping
    public Result<IPage<InventoryExportTaskVO>> page(@Valid @ModelAttribute InventoryExportPageQueryDto query) {
        return Result.success(inventoryExportService.getTaskPage(query));
    }

    /** 不接收企业或创建人ID；Service同时限定企业、本人和任务ID。 */
    @GetMapping("/{id}")
    public Result<InventoryExportTaskVO> detail(@PathVariable("id") Long id) {
        return Result.success(inventoryExportService.getTaskDetail(id));
    }
}
