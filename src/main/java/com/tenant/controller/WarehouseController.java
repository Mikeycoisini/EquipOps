package com.tenant.controller;

import com.common.response.Result;
import com.inventory.transfer.WarehouseCatalogService;
import com.tenant.service.WarehouseService;
import com.equipops.domain.dto.tenant.WarehouseUpsertDto;
import com.equipops.domain.vo.WarehouseVO;
import java.util.List;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** Warehouse 协议入口；业务实现由用户后续手写。 */
@RestController
@RequestMapping("/api/warehouses")
@RequiredArgsConstructor
public class WarehouseController {
    private final WarehouseService warehouseService;
    private final WarehouseCatalogService catalogService;

    /** 查询当前企业启用的默认仓库。 */
    @GetMapping
    public Result<List<WarehouseVO>> list() {
        return Result.success(warehouseService.getDefaultWarehouses());
    }

    /** 在指定厂区登记仓库；tenantId 与是否默认由服务端确定。 */
    @PostMapping
    public Result<WarehouseVO> create(@Valid @RequestBody WarehouseUpsertDto request) {
        return Result.success(warehouseService.createWarehouse(request));
    }

    @GetMapping("/catalog")
    public Result<List<WarehouseCatalogService.Warehouse>> catalog() {
        return Result.success(catalogService.catalog());
    }

    @GetMapping("/{id}")
    public Result<WarehouseCatalogService.Warehouse> detail(@PathVariable long id) {
        return Result.success(catalogService.detail(id));
    }

    @PutMapping("/{id}")
    public Result<WarehouseCatalogService.Warehouse> update(@PathVariable long id,
                                                            @Valid @RequestBody WarehouseUpsertDto request) {
        return Result.success(catalogService.update(id, request));
    }
}
