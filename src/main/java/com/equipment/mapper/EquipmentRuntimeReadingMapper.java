package com.equipment.mapper;

import com.equipops.domain.vo.EquipmentRuntimeReadingVO;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface EquipmentRuntimeReadingMapper {
    @Select("""
            SELECT id FROM equipment
            WHERE tenant_id = #{tenantId} AND site_id = #{siteId} AND id = #{equipmentId}
            FOR UPDATE
            """)
    Long lockEquipment(@Param("tenantId") Long tenantId, @Param("siteId") Long siteId,
            @Param("equipmentId") Long equipmentId);

    @Select("""
            SELECT id, hours, recorded_by AS recordedBy, recorded_at AS recordedAt
            FROM equipment_runtime_readings
            WHERE tenant_id = #{tenantId} AND site_id = #{siteId} AND equipment_id = #{equipmentId}
            ORDER BY id DESC LIMIT #{limit}
            """)
    List<EquipmentRuntimeReadingVO> recent(@Param("tenantId") Long tenantId, @Param("siteId") Long siteId,
            @Param("equipmentId") Long equipmentId, @Param("limit") int limit);

    @Insert("""
            INSERT INTO equipment_runtime_readings
                (tenant_id, site_id, equipment_id, hours, recorded_by, recorded_at)
            VALUES (#{tenantId}, #{siteId}, #{equipmentId}, #{reading.hours}, #{reading.recordedBy}, #{reading.recordedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "reading.id", keyColumn = "id")
    int insert(@Param("tenantId") Long tenantId, @Param("siteId") Long siteId,
            @Param("equipmentId") Long equipmentId, @Param("reading") EquipmentRuntimeReadingVO reading);
}
