package com.tenant.service;

import com.equipops.domain.dto.tenant.SiteUpsertDto;
import com.equipops.domain.vo.SiteVO;
import jakarta.validation.Valid;

import java.util.List;

/** 当前企业厂区查询；必须先验证可信当前租户和有效成员关系。 */
public interface SiteService {
    /** S1 仅返回当前企业启用的默认厂区；初始化缺失须明确报错。 */
    List<SiteVO> getDefaultSites();

    SiteVO getSite(Long id);

    SiteVO createSite(@Valid SiteUpsertDto request);

    SiteVO updateSite(Long id, @Valid SiteUpsertDto request);
}
