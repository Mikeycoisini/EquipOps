package com.equipment.mapper;

import com.equipops.domain.vo.EquipmentRuntimeMaintenanceVO;
import java.math.BigDecimal;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface EquipmentRuntimeMaintenanceMapper {
    @Select("""
            SELECT threshold_hours AS thresholdHours, version, work_order_id AS workOrderId
            FROM equipment_runtime_maintenance
            WHERE tenant_id = #{tenantId} AND site_id = #{siteId} AND equipment_id = #{equipmentId}
            """)
    EquipmentRuntimeMaintenanceVO find(@Param("tenantId") Long tenantId, @Param("siteId") Long siteId,
            @Param("equipmentId") Long equipmentId);

    @Insert("""
            INSERT INTO equipment_runtime_maintenance (tenant_id, site_id, equipment_id, threshold_hours)
            VALUES (#{tenantId}, #{siteId}, #{equipmentId}, #{hours})
            ON DUPLICATE KEY UPDATE threshold_hours = #{hours}, work_order_id = NULL, version = version + 1
            """)
    void save(@Param("tenantId") Long tenantId, @Param("siteId") Long siteId,
            @Param("equipmentId") Long equipmentId, @Param("hours") BigDecimal hours);

    @Update("""
            UPDATE equipment_runtime_maintenance SET work_order_id = #{orderId}
            WHERE tenant_id = #{tenantId} AND site_id = #{siteId} AND equipment_id = #{equipmentId}
              AND threshold_hours = #{hours} AND work_order_id IS NULL
            """)
    int linkOrder(@Param("tenantId") Long tenantId, @Param("siteId") Long siteId,
            @Param("equipmentId") Long equipmentId, @Param("hours") BigDecimal hours, @Param("orderId") Long orderId);
}
