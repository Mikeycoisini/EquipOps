package com.equipment.service.impl;

import java.util.List;
import com.auth.service.CurrentAccessService;
import com.equipment.mapper.EquipmentSparePartMapper;
import com.equipment.service.EquipmentSparePartService;
import com.equipops.domain.dto.equipment.EquipmentSparePartCreateDto;
import com.equipops.domain.entity.EquipmentSparePartEntity;
import com.equipops.domain.entity.SparePartEntity;
import com.equipops.domain.vo.EquipmentSparePartVO;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 设备备件适配关系业务；不修改设备、备件目录或库存。 */
@Service
@RequiredArgsConstructor
public class EquipmentSparePartServiceImpl implements EquipmentSparePartService {
    /** 可信身份、当前企业与管理员校验。 */
    private final CurrentAccessService currentAccessService;
    /** 适配关系及资源归属检查。 */
    private final EquipmentSparePartMapper equipmentSparePartMapper;

    /** 固定按设备、备件顺序锁定，保存与回查同事务提交。 */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public EquipmentSparePartVO createRelation(Long equipmentId, EquipmentSparePartCreateDto request) {
        try {
            //1. 获取可信企业及创建人，仅企业管理员可建立关联。
            Long tenantId = currentAccessService.requireTenantAdminTenantId();
            Long userId = currentAccessService.requireEnabledUserId();
            if (equipmentId == null || equipmentId <= 0 || request == null
                    || request.getSparePartId() == null || request.getSparePartId() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备ID和备件ID必须大于0");
            }
            Long sparePartId = request.getSparePartId();
            //2. 当前读并锁定本企业设备；不额外限制设备必须运行中。
            if (equipmentSparePartMapper.selectTenantEquipmentForUpdate(tenantId, equipmentId) == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在");
            }
            //3. 当前读并锁定本企业备件，确保检查通过后不会在提交前被停用。
            SparePartEntity part = equipmentSparePartMapper.selectTenantSparePartForUpdate(tenantId, sparePartId);
            if (part == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "备件不存在");
            }
            if (!Boolean.TRUE.equals(part.getEnabled())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "备件已停用，不能建立新的适配关系");
            }
            //4. 读已提交配合设备行锁使相同设备的关联串行处理，数据库唯一键再防一次重复。
            if (equipmentSparePartMapper.findRelationId(tenantId, equipmentId, sparePartId) != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "该设备已关联此备件");
            }
            //5. 只写适配关系，创建人由登录上下文确定，主键和时间由数据库生成。
            EquipmentSparePartEntity relation = new EquipmentSparePartEntity();
            relation.setTenantId(tenantId);
            relation.setEquipmentId(equipmentId);
            relation.setSparePartId(sparePartId);
            relation.setCreatedBy(userId);
            try {
                if (equipmentSparePartMapper.insertRelation(relation) != 1 || relation.getId() == null) {
                    throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "适配关系保存失败");
                }
            } catch (DuplicateKeyException exception) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "该设备已关联此备件");
            }
            //6. 按企业和新关系ID回查完整资料；回查不隐藏停用备件，便于后续追溯。
            EquipmentSparePartVO value = equipmentSparePartMapper.selectRelationDetail(tenantId, relation.getId());
            if (value == null) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "适配关系保存后查询失败");
            }
            return value;
        } catch (PessimisticLockingFailureException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "并发处理冲突，请稍后重试");
        }
    }
    /** 只读查询，不锁定设备或备件，停用备件仍可追溯。 */
    @Override
    @Transactional(readOnly = true)
    public List<EquipmentSparePartVO> listRelations(Long equipmentId) {
        //1. 验证当前账号、企业和成员关系，普通有效成员即可查看。
        Long tenantId = currentAccessService.requireActiveTenantId();
        //2. 检查路径中的设备ID，企业ID始终取自可信上下文。
        if (equipmentId == null || equipmentId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备ID必须大于0");
        }
        //3. 先区分设备不存在与设备没有适配关系；其他企业设备统一返回404。
        if (equipmentSparePartMapper.selectTenantEquipment(tenantId, equipmentId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在");
        }
        //4、5. XML关联查询当前资料，包含停用备件；MyBatis无记录时返回空列表。
        return equipmentSparePartMapper.selectRelationsByEquipment(tenantId, equipmentId);
    }

    /** 只删除适配关系，不改变备件、库存或历史记录。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeRelation(Long equipmentId, Long sparePartId) {
        // 企业身份来自登录上下文，不接受前端指定。
        Long tenantId = currentAccessService.requireTenantAdminTenantId();
        if (equipmentId == null || equipmentId <= 0 || sparePartId == null || sparePartId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备ID和备件ID必须大于0");
        }
        // 按企业、设备、备件同时限定，未找到关系返回404。
        if (equipmentSparePartMapper.deleteRelation(tenantId, equipmentId, sparePartId) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "适配关系不存在");
        }
    }

    /** 只读反向查询，不限制备件启用状态或设备运行状态。 */
    @Override
    @Transactional(readOnly = true)
    public List<EquipmentSparePartVO> listRelationsBySparePart(Long sparePartId) {
        //1. 有效普通成员即可查询，企业ID不由前端指定。
        Long tenantId = currentAccessService.requireActiveTenantId();
        //2. 路径ID是备件ID，必须为正整数。
        if (sparePartId == null || sparePartId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "备件ID必须大于0");
        }
        //3. 普通SELECT检查归属，不加锁；其他企业备件统一按不存在处理。
        if (equipmentSparePartMapper.selectTenantSparePart(tenantId, sparePartId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "备件不存在");
        }
        //4、5. 不过滤停用备件或停机设备，没有适配关系时MyBatis返回空列表。
        return equipmentSparePartMapper.selectRelationsBySparePart(tenantId, sparePartId);
    }

}
