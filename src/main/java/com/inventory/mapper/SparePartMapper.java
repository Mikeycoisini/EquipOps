package com.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.equipops.domain.entity.SparePartEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** spare_parts 表基础操作；调用方必须显式添加当前企业条件。 */
@Mapper
public interface SparePartMapper extends BaseMapper<SparePartEntity> {
    /** 修改当前企业的备件目录资料，不修改库存数量。 */
    int updateCatalog(@Param("tenantId") Long tenantId,
                      @Param("part") SparePartEntity part);
}
