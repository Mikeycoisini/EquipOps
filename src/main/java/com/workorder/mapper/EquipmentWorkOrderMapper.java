package com.workorder.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.equipops.domain.entity.WorkOrderEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 设备关联工单查询；SQL 位于同名 XML 中。 */
@Mapper
public interface EquipmentWorkOrderMapper {
    /** 同时限定企业与设备，分页由 MyBatis-Plus 插件处理。 */
    IPage<WorkOrderEntity> selectWorkOrderPage(Page<WorkOrderEntity> page,
            @Param("tenantId") Long tenantId, @Param("equipmentId") Long equipmentId);
}