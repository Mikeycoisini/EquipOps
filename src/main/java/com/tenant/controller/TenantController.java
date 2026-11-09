package com.tenant.controller;

import com.common.response.Result;
import com.tenant.service.TenantService;
import com.equipops.domain.vo.TenantVO;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tenants")
@RequiredArgsConstructor
public class TenantController {
    private final TenantService tenantService;

    /** 查询当前登录用户可进入的企业，不接受客户端用户 ID 作为身份。 */
    @GetMapping("/mine")
    public Result<List<TenantVO>> mine() {
        return Result.success(tenantService.getMyTenants());
    }
}
