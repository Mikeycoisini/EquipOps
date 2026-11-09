package com.tenant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.equipops.domain.entity.TenantEntity;

/** tenants 表基础操作；租户查询条件由业务实现明确添加。 */
public interface TenantMapper extends BaseMapper<TenantEntity> {
}
