package com.tenant.controller;

import com.common.response.Result;
import com.tenant.service.TenantMemberService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.equipops.domain.dto.tenant.TenantMemberCreateDto;
import com.equipops.domain.dto.tenant.TenantMemberRolesUpdateDto;
import com.equipops.domain.dto.tenant.TenantMemberStatusUpdateDto;
import com.equipops.domain.vo.TenantMemberVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** TenantMember 协议入口 */
@RestController
@RequestMapping("/api/tenant-members")
@RequiredArgsConstructor
public class TenantMemberController {
    private final TenantMemberService tenantMemberService;

    /** 分页查询当前企业成员*/
    @GetMapping
    public Result<IPage<TenantMemberVO>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean enabled) {
        return Result.success(tenantMemberService.getMemberPage(current, size, keyword, enabled));
    }

    /** 将已有全局账号加入当前企业。 */
    @PostMapping
    public Result<TenantMemberVO> add(@Valid @RequestBody TenantMemberCreateDto request) {
        return Result.success(tenantMemberService.addMember(request));
    }

    /** 修改企业成员角色；路径 ID 是成员关系 ID。 */
    @PutMapping("/{id}/roles")
    public Result<TenantMemberVO> updateRoles(
            @PathVariable("id") Long id,
            @Valid @RequestBody TenantMemberRolesUpdateDto request) {
        return Result.success(tenantMemberService.updateMemberRoles(id, request));
    }

    /** 启用或撤销成员关系。 */
    @PatchMapping("/{id}/status")
    public Result<TenantMemberVO> updateStatus(
            @PathVariable("id") Long id,
            @Valid @RequestBody TenantMemberStatusUpdateDto request) {
        return Result.success(tenantMemberService.updateMemberStatus(id, request));
    }
}
