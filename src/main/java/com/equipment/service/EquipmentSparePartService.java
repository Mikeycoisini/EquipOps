package com.equipment.service;

import java.util.List;
import com.equipops.domain.dto.equipment.EquipmentSparePartCreateDto;
import com.equipops.domain.vo.EquipmentSparePartVO;

/** 具体设备的适用备件关联业务。 */
public interface EquipmentSparePartService {
    /** 当前企业管理员建立适配关系，不改变库存或领料限制。 */
    EquipmentSparePartVO createRelation(Long equipmentId, EquipmentSparePartCreateDto request);
    /** 当前企业有效成员查询设备适配关系，按关联ID倒序返回。 */
    List<EquipmentSparePartVO> listRelations(Long equipmentId);

    /** 当前企业管理员解除适配关系。 */
    void removeRelation(Long equipmentId, Long sparePartId);

    /** 当前企业有效成员从备件反查适配设备，停用备件仍可查看已有关系。 */
    List<EquipmentSparePartVO> listRelationsBySparePart(Long sparePartId);

}
