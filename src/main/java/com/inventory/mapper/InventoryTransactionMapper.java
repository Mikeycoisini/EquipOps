package com.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.equipops.domain.dto.inventory.InventoryTransactionPageQueryDto;
import com.equipops.domain.entity.InventoryTransactionEntity;
import com.equipops.domain.vo.InventoryTransactionVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** inventory_transactions 表基础操作；调用方必须显式添加当前企业条件。 */
@Mapper
public interface InventoryTransactionMapper extends BaseMapper<InventoryTransactionEntity> {
    IPage<InventoryTransactionVO> selectPageWithDetails(
            IPage<InventoryTransactionVO> page,
            @Param("tenantId") Long tenantId,
            @Param("query") InventoryTransactionPageQueryDto query
    );

    /** 按可信企业ID和流水ID查询详情，关联资料缺失时仍返回原流水。 */
    InventoryTransactionVO selectDetailWithDetails(
            @Param("tenantId") Long tenantId, @Param("id") Long id);
}
