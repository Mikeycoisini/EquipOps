package com.tenant.controller;

import com.common.response.Result;
import com.equipops.domain.dto.tenant.SiteScopeUpdateDto;
import com.equipops.domain.vo.SiteScopeVO;
import com.tenant.service.SiteScopeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 企业管理员维护成员显式厂区范围；企业及操作者始终由服务端验证。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/tenant-members/{membershipId}/site-scopes")
public class SiteScopeController {
    private final SiteScopeService service;

    @GetMapping
    public Result<SiteScopeVO> get(@PathVariable("membershipId") Long membershipId) {
        // 1. 传递目标成员关系ID，管理员与企业校验由服务执行。
        return Result.success(service.getScopes(membershipId));
    }

    @PutMapping
    public Result<SiteScopeVO> put(@PathVariable("membershipId") Long membershipId, @Valid @RequestBody SiteScopeUpdateDto request) {
        // 1. 通过版本条件完整替换集合，空数组表示撤销全部普通授权。
        return Result.success(service.updateScopes(membershipId, request));
    }
}
