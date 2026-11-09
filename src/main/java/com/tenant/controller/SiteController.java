package com.tenant.controller;

import com.common.response.Result;
import com.equipops.domain.dto.tenant.SiteUpsertDto;
import com.tenant.service.SiteService;
import com.equipops.domain.vo.SiteVO;
import java.util.List;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** Site 协议入口 */
@RestController
@RequestMapping("/api/sites")
@RequiredArgsConstructor
public class SiteController {
    private final SiteService siteService;

    /** 查询当前企业启用的默认厂区。 */
    @GetMapping
    public Result<List<SiteVO>> list() {
        return Result.success(siteService.getDefaultSites());
    }

    /** 查询当前企业指定厂区。 */
    @GetMapping("/{id}")
    public Result<SiteVO> detail(@PathVariable("id") Long id) {
        return Result.success(siteService.getSite(id));
    }
    /** 登记厂区；tenantId 由服务端从登录上下文取得。 */
    @PostMapping
    public Result<SiteVO> create(@Valid @RequestBody SiteUpsertDto request) {
        return Result.success(siteService.createSite(request));
    }
    /** 修改厂区资料。 */
    @PutMapping("/{id}")
    public Result<SiteVO> update(@PathVariable("id") Long id,
                                 @Valid @RequestBody SiteUpsertDto request) {
        return Result.success(siteService.updateSite(id, request));
    }
}
