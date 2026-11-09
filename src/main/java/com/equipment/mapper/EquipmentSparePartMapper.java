package com.equipment.mapper;

import java.util.List;
import com.equipops.domain.entity.EquipmentEntity;
import com.equipops.domain.entity.EquipmentSparePartEntity;
import com.equipops.domain.entity.SparePartEntity;
import com.equipops.domain.vo.EquipmentSparePartVO;
import org.apache.ibatis.annotations.Param;

/** 适配关系操作，已由com.equipment.mapper扫描；SQL集中在XML。 */
public interface EquipmentSparePartMapper {
    /** 当前读并锁定指定企业的设备。 */
    EquipmentEntity selectTenantEquipmentForUpdate(@Param("tenantId") Long tenantId, @Param("equipmentId") Long equipmentId);
    /** 当前读并锁定指定企业的备件。 */
    SparePartEntity selectTenantSparePartForUpdate(@Param("tenantId") Long tenantId, @Param("sparePartId") Long sparePartId);
    /** 检查设备和备件是否已有关系。 */
    Long findRelationId(@Param("tenantId") Long tenantId, @Param("equipmentId") Long equipmentId, @Param("sparePartId") Long sparePartId);
    /** 插入关系，并回填relation.id。 */
    int insertRelation(@Param("relation") EquipmentSparePartEntity relation);
    /** 回查当前企业的关系及关联资料，不过滤备件启用状态。 */
    EquipmentSparePartVO selectRelationDetail(@Param("tenantId") Long tenantId, @Param("id") Long id);
    /** 普通查询本企业设备，供只读接口校验归属，不加行锁。 */
    EquipmentEntity selectTenantEquipment(@Param("tenantId") Long tenantId, @Param("equipmentId") Long equipmentId);
    /** 查询本企业指定设备的全部适配关系，包含停用备件。 */
    List<EquipmentSparePartVO> selectRelationsByEquipment(@Param("tenantId") Long tenantId, @Param("equipmentId") Long equipmentId);

    /** 按企业、设备和备件删除唯一的适配关系。 */
    int deleteRelation(@Param("tenantId") Long tenantId, @Param("equipmentId") Long equipmentId, @Param("sparePartId") Long sparePartId);

    /** 普通查询本企业备件，不加行锁、不筛选启用状态。 */
    SparePartEntity selectTenantSparePart(@Param("tenantId") Long tenantId, @Param("sparePartId") Long sparePartId);
    /** 返回本企业指定备件的适配关系及设备资料，按关系ID倒序。 */
    List<EquipmentSparePartVO> selectRelationsBySparePart(@Param("tenantId") Long tenantId, @Param("sparePartId") Long sparePartId);

}
