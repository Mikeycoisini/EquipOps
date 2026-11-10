package com.tenant.service;

import com.equipops.domain.dto.tenant.SiteScopeUpdateDto;
import com.equipops.domain.vo.SiteScopeVO;

/** 企业管理员读取或原子替换成员的显式厂区集合。 */
public interface SiteScopeService {
    SiteScopeVO getScopes(Long membershipId);
    SiteScopeVO updateScopes(Long membershipId, SiteScopeUpdateDto request);
}
