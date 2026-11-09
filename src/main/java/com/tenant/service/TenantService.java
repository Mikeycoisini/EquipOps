package com.tenant.service;

import com.equipops.domain.vo.TenantVO;
import java.util.List;

/** 企业查询契约；当前登录用户必须来自可信认证上下文。 */
public interface TenantService {
    /** 仅返回当前用户账号有效、成员关系有效且企业启用的企业。 */
    List<TenantVO> getMyTenants();
}
