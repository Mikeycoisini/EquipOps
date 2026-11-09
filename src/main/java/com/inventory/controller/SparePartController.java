package com.inventory.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.common.response.Result;
import com.equipops.domain.dto.inventory.SparePartPageQueryDto;
import com.equipops.domain.dto.inventory.SparePartUpsertDto;
import com.equipops.domain.vo.SparePartVO;
import com.inventory.service.SparePartService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 备件目录接口入口；企业归属、唯一性和写入权限由业务层检查。 */
@RestController
@RequestMapping("/api/spare-parts")
@RequiredArgsConstructor
public class SparePartController {
    /** 备件目录业务。 */
    private final SparePartService sparePartService;

    /** 分页查询备件编号、名称、分类及启用状态。 */
    @GetMapping
    public Result<IPage<SparePartVO>> page(@Valid @ModelAttribute SparePartPageQueryDto query) {
        // URL 查询参数绑定到 query，@Valid 检查参数；Service 负责企业范围及分页。
        return Result.success(sparePartService.getSparePartPage(query));
    }

    /** 查看当前企业指定备件。 */
    @GetMapping("/{id}")
    public Result<SparePartVO> detail(@PathVariable("id") Long id) {
        // id 来自 URL 路径；Service 必须确认对象属于当前企业，再返回资料。
        return Result.success(sparePartService.getSparePartDetail(id));
    }

    /** 新增备件目录，不能在请求中设置库存数量。 */
    @PostMapping
    public Result<SparePartVO> add(@Valid @RequestBody SparePartUpsertDto request) {
        // request 来自 JSON 请求体；@Valid 检查字段，Service 负责权限与数据库操作。
        return Result.success(sparePartService.addSparePart(request));
    }

    /** 修改备件目录，不能修改企业归属及库存数量。 */
    @PutMapping("/{id}")
    public Result<SparePartVO> update(@PathVariable("id") Long id,
                                      @Valid @RequestBody SparePartUpsertDto request) {
        // request 来自 JSON 请求体；@Valid 检查字段，Service 负责权限与数据库操作。
        return Result.success(sparePartService.updateSparePart(id, request));
    }
}
