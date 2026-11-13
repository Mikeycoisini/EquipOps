package com.equipment.service.impl;

import com.auth.service.CurrentAccessService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.equipment.mapper.EquipmentMapper;
import com.equipment.service.EquipmentService;
import com.equipops.domain.dto.equipment.EquipmentUpsertDto;
import com.equipops.domain.entity.EquipmentEntity;
import com.equipops.domain.entity.TenantMembershipEntity;
import com.equipops.domain.entity.UserEntity;
import com.equipops.domain.enums.EquipmentStatus;
import com.equipops.domain.vo.EquipmentVO;
import com.equipops.domain.vo.SiteVO;
import com.tenant.mapper.TenantMembershipMapper;
import com.tenant.service.SiteService;
import com.workorder.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class EquipmentServiceImpl extends ServiceImpl<EquipmentMapper, EquipmentEntity> implements EquipmentService {
    /** INSERT不经过SQL权限插件，新增前锁定可信授权和厂区。 */
    @org.springframework.beans.factory.annotation.Autowired
    private com.auth.scope.SiteWriteAccessService siteWriteAccess;

    private final EquipmentMapper equipmentMapper;
    private final UserMapper userMapper;
    /**
     * 验证可信当前企业，不能直接使用请求体中的企业 ID。
     */
    private final CurrentAccessService currentAccessService;
    /**
     * 查询当前企业启用的默认厂区。
     */
    private final SiteService siteService;
    /**
     * 检查设备负责人是否为当前企业的启用成员。
     */
    private final TenantMembershipMapper membershipMapper;

    /**
     * 添加设备
     *
     * @param equipment
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public EquipmentEntity addEquipment(EquipmentEntity equipment) {
        Long tenantId = currentAccessService.requireActiveTenantId();
        SiteVO site = siteService.getDefaultSites().get(0);
        // 新增前按当前读锁定成员、角色及厂区授权，不能沿用旧RR快照。
        siteWriteAccess.lockSite(site.getId());
        // 即使请求体填写了其他归属，也必须由服务端覆盖。
        equipment.setTenantId(tenantId);
        equipment.setSiteId(site.getId());
        validateManager(tenantId, equipment.getManagerUserId());
        //先给设备设置空
        equipment.setId(null);
        // 新登记的设备初始为未运行状态，启动后再变更状态。
        equipment.setStatus(EquipmentStatus.STOPPED);
        //添加设备
        int addrows = equipmentMapper.insert(equipment);
        //如果添加设备失败，则抛出异常
        if (addrows != 1) {
            throw new IllegalStateException("添加设备失败");
        }
        return equipment;
    }

    /**
     * 删除设备
     *
     * @param
     * @return
     */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public EquipmentEntity deleteEquipment(Long id) {
        // 根据 ID 去数据库查询真实的设备信息
        EquipmentEntity equipment = requireEquipment(id, currentAccessService.requireActiveTenantId());
        // 判断设备是否真的存在
        if (equipment == null) {
            throw new IllegalArgumentException("设备不存在");
        }
        if (equipment.getStatus() == EquipmentStatus.RUNNING) {
            throw new IllegalStateException("设备正在运行中,请勿删除");
        }
        // 删除条件仍带企业和原状态，避免查询后状态变化时误删运行中的设备。
        try {
            if (equipmentMapper.delete(new LambdaQueryWrapper<EquipmentEntity>()
                    .eq(EquipmentEntity::getId, id)
                    .eq(EquipmentEntity::getTenantId, equipment.getTenantId())
                    .eq(EquipmentEntity::getStatus, equipment.getStatus())) != 1) {
                throw new IllegalStateException("设备删除失败");
            }
        } catch (DataIntegrityViolationException exception) {
            // 外键同时保护删除和抄表并发，命中读数约束时返回可操作的提示。
            String detail = exception.getMostSpecificCause().getMessage();
            if (detail != null && detail.contains("fk_runtime_reading_equipment")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "设备已有运行小时读数，不能删除");
            }
            throw exception;
        }
        //返回设备
        return equipment;
    }

    /**
     * 修改设备,取得 ID → 查询 existingById → 修改 existingById → 保存 existingById → 返回 existingById
     *
     * @param equipment
     * @param request
     * @return
     */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public EquipmentEntity updateEquipment(EquipmentEntity equipment, EquipmentUpsertDto request) {
        //接收路径中的设备 ID 和 JSON 修改数据
        Long id = equipment.getId();
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("设备 ID 无效");
        }
        //根据前端返回的ID查询数据库设备
        Long tenantId = currentAccessService.requireActiveTenantId();
        EquipmentEntity existingById = requireEquipment(id, tenantId);
        validateManager(tenantId, request.getManagerUserId());

        //从前端获取到填写的ID
        Long managerUserId = request.getManagerUserId();
        if (managerUserId != null) {
            UserEntity userEntity = userMapper.selectById(managerUserId);
            if (userEntity == null) {
                //抛出400客户端错误异常
                throw new ResponseStatusException(HttpStatusCode.valueOf(400), "该设备负责人不存在，请选择有效用户！");
            }
        }

        if (existingById == null) {
            throw new IllegalArgumentException("设备不存在！");
        }
        //判断负责人ID是否为空
        request.getManagerUserId();//从前端获取到负责人ID

        //把设备的类复制到request
        // 归属、主键、审计列不允许编辑；运行状态由独立状态接口处理。
        BeanUtils.copyProperties(request, existingById,
                "id", "tenantId", "siteId", "status", "createdAt", "updatedAt");
        //修改设备
        boolean updated = this.update(existingById, new LambdaUpdateWrapper<EquipmentEntity>()
                .eq(EquipmentEntity::getId, id)
                .eq(EquipmentEntity::getTenantId, tenantId));
        if (!updated) {
            throw new IllegalStateException("设备修改失败");
        }
        return existingById;
    }

    @Override
    public IPage<EquipmentEntity> getEquipmentPage(long current, long size, String keyword, EquipmentStatus status) {
        Long tenantId = currentAccessService.requireActiveTenantId();
        //校验页码和每页数量（防前端乱传导致数据库崩溃）
        if (current < 1) {//当前页面
            current = 1;
        }
        //最小页码为1
        if (size < 1) {
            size = 1;
        } else if (size > 100) {
            size = 100;
        }
        // 1. 构造分页参数
        Page<EquipmentEntity> page = new Page<>(current, size);
        // 2. 构造查询条件
        LambdaQueryWrapper<EquipmentEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(EquipmentEntity::getTenantId, tenantId);

        // 如果有关键字，按设备名称模糊查询
        wrapper.like(StringUtils.hasText(keyword), EquipmentEntity::getName, keyword);
        // 有名称关键字时，按设备名称模糊查询。
        wrapper.like(
                StringUtils.hasText(keyword),
                EquipmentEntity::getName,
                keyword
        );
        // 有状态参数时，按状态精确查询。
        wrapper.eq(
                status != null,
                EquipmentEntity::getStatus,
                status
        );
        // 按 ID 倒序
        wrapper.orderByDesc(EquipmentEntity::getId);
        // 3. 调用 MyBatis-Plus 自带的 page 方法（底层自动执行 SQL 的 count 和 limit）
        return this.page(page, wrapper);
    }

    /**
     * 查询设备状态
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public EquipmentEntity getEquipStatusById(Long id, EquipmentStatus targetStatus) {
        //1.根据 ID 查询设备
        EquipmentEntity equipment = requireEquipment(id, currentAccessService.requireActiveTenantId());
        //2.设备不存在 → 返回不存在的错误
        if (equipment == null) {
            throw new IllegalArgumentException("设备不存在!");
        }
        //3.检查当前状态是否允许本次操作
        EquipmentStatus status = equipment.getStatus();
        if (targetStatus == null) {
            throw new IllegalStateException("目标状态不能为空！");
        }
        //targetStatus：目标状态
        if (status == targetStatus) {
            throw new IllegalStateException("该设备已经是该状态,无需重复操作。");
        }
        //不允许 → 返回业务冲突的错误
        // 运行中的设备不允许直接停用（你可以根据实际业务加判断）
        /** 这段代码会拦截正常停机,错误示范
         if (EquipmentStatus.RUNNING == status && targetStatus == EquipmentStatus.STOPPED) {
         throw new IllegalStateException("设备正在运行，请先停机!");
         }*/

        /** 正向逻辑校验,白名单法*/
        //只有当设备当前是停机状态，且目标是运行状态时,才能开机
        boolean canStart = status == EquipmentStatus.STOPPED && targetStatus == EquipmentStatus.RUNNING;
        //只有当设备当前是运行状态，且目标是停机状态时，才能停机
        boolean canStop = status == EquipmentStatus.RUNNING && targetStatus == EquipmentStatus.STOPPED;
        if (!canStart && !canStop) {
            throw new IllegalStateException("当前设备状态不允许执行该操作");
        }//既不是合法时启动，也不是合法的停

        //4.仅在数据库中的原状态仍符合条件时，更新状态
        /***
         * 基于 CAS（Compare-And-Swap）思想的状态条件更新，常用于并发场景下防止"状态覆盖"问题。
         * MySQL 的行锁 + WHERE 条件
         * 生成sql语句：UPDATE equipment SET status = 'STOPPED' WHERE id = 1 AND status = 'RUNNING';
         */
        //使用MyBatis-Plus提供的一个更新条件构造器LambdaUpdateWrapper进行关键字指定操作
        LambdaUpdateWrapper<EquipmentEntity> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(EquipmentEntity::getId, id)
                .eq(EquipmentEntity::getTenantId, equipment.getTenantId())
                .eq(EquipmentEntity::getStatus, status)
                .set(EquipmentEntity::getStatus, targetStatus);
        // 执行更新，只更新状态字段，效率更高，也不会覆盖其他字段
        boolean updateSuccess = this.update(wrapper);
        //5.更新失败 → 提示状态已变化，需要刷新
        if (!updateSuccess) {
            throw new IllegalStateException("该设备已经更新，请刷新界面！");
        }
        //更新当前设备状态
        equipment.setStatus(targetStatus);
        //6.返回操作后的设备信息
        return equipment;
    }

    /**
     * 详情复用企业范围查询，转换为页面 VO。
     */
    @Override
    public EquipmentVO getEquipmentDetail(Long id) {
        //现获取到当先可信企业
        //  验证登录用户和当前企业成员关系，再按企业 ID + 设备 ID 查询。
        EquipmentEntity equipment = requireEquipment(id, currentAccessService.requireActiveTenantId());
        EquipmentVO vo = new EquipmentVO();
        BeanUtils.copyProperties(equipment, vo);
        return vo;
    }

    /**
     * 按企业和设备 ID 查询，避免猜测其他企业的设备 ID。
     */
    private EquipmentEntity requireEquipment(Long id, Long tenantId) {
        // 先检查 ID，避免空值或负数进入数据库查询。
        if (id == null || id <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备 ID 无效");
        // 两个 eq 通过 AND 连接：不能只按 ID 查询，否则可能读到其他企业设备。
        EquipmentEntity equipment = equipmentMapper.selectOne(
                new LambdaQueryWrapper<EquipmentEntity>()
                        .eq(EquipmentEntity::getTenantId, tenantId)
                        .eq(EquipmentEntity::getId, id));
        // 查不到和不属于本企业采用相同提示，不泄露其他企业的数据。
        if (equipment == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在");
        return equipment;
    }

    /**
     * 负责人可不填；填写时必须是启用账号及当前企业的启用成员。
     */
    private void validateManager(Long tenantId, Long userId) {
        if (userId == null) return;
        UserEntity user = userMapper.selectById(userId);
        Long count = membershipMapper.selectCount(new LambdaQueryWrapper<TenantMembershipEntity>()
                .eq(TenantMembershipEntity::getTenantId, tenantId)
                .eq(TenantMembershipEntity::getUserId, userId)
                .eq(TenantMembershipEntity::getEnabled, true));
        if (user == null || !Boolean.TRUE.equals(user.getEnabled()) || count == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "负责人必须是当前企业的有效成员");
        }
    }
}
