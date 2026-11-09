package com.tenant.service;

import com.equipops.domain.dto.tenant.WarehouseUpsertDto;
import com.equipops.domain.vo.WarehouseVO;
import java.util.List;

/** 当前企业仓库查询与登记；必须先验证可信当前租户和有效成员关系。 */
public interface WarehouseService {
    /** S1 仅返回当前企业启用的默认仓库；初始化缺失须明确报错。 */
    List<WarehouseVO> getDefaultWarehouses();

    /** 在当前企业指定厂区登记仓库；tenantId 由服务端上下文确定。 */
    WarehouseVO createWarehouse(WarehouseUpsertDto request);
}
