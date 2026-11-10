package com.tenant.service.impl;

import com.auth.service.CurrentAccessService;
import com.audit.service.AuditEventService;
import com.equipops.domain.dto.audit.AuditAppendCommand;
import com.equipops.domain.enums.AuditAction;
import com.equipops.domain.enums.AuditObjectType;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.equipops.domain.dto.tenant.SiteUpsertDto;
import com.equipops.domain.entity.EquipmentEntity;
import com.equipops.domain.entity.SiteEntity;
import com.equipops.domain.entity.WorkOrderEntity;
import com.equipops.domain.enums.EquipmentStatus;
import com.equipops.domain.enums.WorkOrderStatus;
import com.equipops.domain.vo.SiteVO;
import com.equipment.mapper.EquipmentMapper;
import com.tenant.mapper.SiteMapper;
import com.tenant.service.SiteService;
import com.workorder.mapper.WorkOrderMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Objects;

@Service
public class SiteServiceImpl implements SiteService {
    @Autowired
    private CurrentAccessService currentAccessService;

    @Autowired
    private SiteMapper siteMapper;

    /** 成功事件参与厂区业务事务，审计失败必须回滚业务。 */
    @Autowired
    private AuditEventService auditEventService;

    /** 停用厂区前统计仍在该厂区的活跃设备。 */
    @Autowired
    private EquipmentMapper equipmentMapper;

    /** 停用厂区前统计该厂区未结束的工单。 */
    @Autowired
    private WorkOrderMapper workOrderMapper;

    @Override
    public List<SiteVO> getDefaultSites() {
        //查询默认厂区
        // 1. 必须先确认当前用户具有有效的企业成员关系。
        Long tenantId = currentAccessService.requireActiveTenantId();

        // 2. 只查询当前企业启用的默认厂区。
        LambdaQueryWrapper<SiteEntity> wrapper = new LambdaQueryWrapper<>();

        wrapper.eq(SiteEntity::getTenantId, tenantId)
                .eq(SiteEntity::getIsDefault, true)
                .eq(SiteEntity::getEnabled, true);

        SiteEntity site = siteMapper.selectOne(wrapper);

        // 3. 默认组织缺失时，明确提示配置问题。
        if (site == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "当前企业的默认厂区未初始化或未启用");
        }

        // 4. 转换为前端返回对象。
        SiteVO vo = new SiteVO();
        BeanUtils.copyProperties(site, vo);

        return List.of(vo);
    }

    @Override
    public List<SiteVO> getSiteCatalog() {
        // 1. 每次查询重新验证账号、企业和成员；厂区范围由统一数据权限插件加入。
        Long tenantId = currentAccessService.requireActiveTenantId();
        // 2. 目录包含可见的启停厂区，便于管理员维护；不改变默认厂区GET的语义。
        List<SiteEntity> sites = siteMapper.selectList(new LambdaQueryWrapper<SiteEntity>()
                .eq(SiteEntity::getTenantId, tenantId).orderByAsc(SiteEntity::getId));
        // 3. 仅转换已受企业及厂区范围约束的记录。
        return sites.stream().map(site -> {
            SiteVO vo = new SiteVO();
            BeanUtils.copyProperties(site, vo);
            return vo;
        }).toList();
    }

    @Override
    public SiteVO getSite(Long id) {
        //1.获取当前身份与当前企业
        Long tenantId = currentAccessService.requireActiveTenantId();
        if(id==null || id<=0){
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "厂区 ID 无效");
        }
        //2. 检查是否选择了企业
       SiteEntity site= siteMapper.selectOne(
                new LambdaQueryWrapper<SiteEntity>()
                        .eq(SiteEntity::getTenantId, tenantId)
                        .eq(SiteEntity::getId, id));
        if (site == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "厂区不存在");
        }
        //3.返回结果
        SiteVO vo = new SiteVO();
        BeanUtils.copyProperties(site, vo);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SiteVO createSite(SiteUpsertDto request) {
        // 1. 创建厂区仅允许当前企业管理员，企业归属不读取请求体。
        Long tenantId = currentAccessService.requireTenantAdminTenantId();
        // 2. 分别校验编号和名称，服务直接调用同样拒绝空参数。
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求不能为空");
        }
        String siteCode = request.getSiteCode() == null ? "" : request.getSiteCode().trim();
        String name = request.getName() == null ? "" : request.getName().trim();
        if (siteCode.isEmpty() || name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "厂区编号和厂区名称不能为空");
        }
        // 3. 将可空地址规范化，避免空白文字落库。
        String address = request.getAddress() == null ? null : request.getAddress().trim();
        if (address != null && address.isEmpty()) address = null;
        // 4. 企业内编号预检查提升错误提示，数据库唯一键处理并发竞争。
        Long count = siteMapper.selectCount(new LambdaQueryWrapper<SiteEntity>()
                .eq(SiteEntity::getTenantId, tenantId).eq(SiteEntity::getSiteCode, siteCode));
        if (count != null && count > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "厂区编号已存在");
        }
        // 5. 服务端固定企业及非默认属性；管理员可在本企业创建新厂区。
        SiteEntity site = SiteEntity.builder().tenantId(tenantId).siteCode(siteCode).name(name)
                .address(address).enabled(request.getEnabled() == null || request.getEnabled())
                .isDefault(false).build();
        // 6. 真正持久化并检查数据库回填主键，禁止返回未落库的假成功。
        try {
            if (siteMapper.insert(site) != 1 || site.getId() == null) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "厂区保存失败");
            }
        } catch (DuplicateKeyException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "厂区编号已存在");
        }
        // 7. 回查数据库生成时间，读取失败则整个事务回滚。
        SiteVO saved = getSite(site.getId());
        // 8. 成功审计只记录布尔配置和可信对象ID，名称、地址、编号正文均不保存。
        auditEventService.appendSuccess(new AuditAppendCommand(tenantId, currentAccessService.requireEnabledUserId(),
                AuditAction.SITE_CREATED, AuditObjectType.SITE, saved.getId(),
                Map.of("enabled", saved.getEnabled(), "isDefault", saved.getIsDefault()), null, null));
        // 9. 业务与成功审计一起提交；审计异常不吞掉。
        return saved;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SiteVO updateSite(Long id, SiteUpsertDto request) {
        //1.获取当前操作者与企业
        Long tenantId = currentAccessService.requireTenantAdminTenantId();
        //2.校验并规范化参数
        if (id == null || id <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "厂区 ID 无效");
        }
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求不能为空");
        }
        String trSiteCode = request.getSiteCode() == null ? "" : request.getSiteCode().trim();
        String trName = request.getName() == null ? "" : request.getName().trim();
        if (trSiteCode.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "厂区编号不能为空");
        }
        if (trName.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "厂区名称不能为空");
        }
        String address = request.getAddress();
        if (address != null) {
            address = address.trim();
            if (address.isEmpty()) {
                address = null;
            }
        }
        //3.锁定当前企业的目标厂区，查不到统一返回 404
        SiteEntity site = siteMapper.selectOne(
                new LambdaQueryWrapper<SiteEntity>()
                        .eq(SiteEntity::getTenantId, tenantId)
                        .eq(SiteEntity::getId, id)
                        .last("FOR UPDATE"));
        if (site == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "厂区不存在");
        }
        //4.厂区编号不可修改，编号已被设备和仓库等外部数据引用
        if (!trSiteCode.equals(site.getSiteCode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "厂区编号不允许修改");
        }
        Boolean targetEnabled = request.getEnabled() == null ? site.getEnabled() : request.getEnabled();
        //5.停用时检查默认厂区、活跃设备和未结束工单
        if (Boolean.TRUE.equals(site.getEnabled()) && !Boolean.TRUE.equals(targetEnabled)) {
            if (Boolean.TRUE.equals(site.getIsDefault())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "默认厂区不能停用，请先切换默认厂区");
            }
            Long actEqu = equipmentMapper.selectCount(
                    new LambdaQueryWrapper<EquipmentEntity>()
                            .eq(EquipmentEntity::getTenantId, tenantId)
                            .eq(EquipmentEntity::getSiteId, id)
                            //  条件3：状态属于（运行中 OR 需要关注）
                            .in(EquipmentEntity::getStatus, EquipmentStatus.RUNNING, EquipmentStatus.NEEDS_ATTENTION));
            if (actEqu != null && actEqu > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "该厂区仍有运行中的设备，不能停用");
            }
            Long openWorkOrders = workOrderMapper.selectCount(
                    new LambdaQueryWrapper<WorkOrderEntity>()
                            .eq(WorkOrderEntity::getTenantId, tenantId)
                            .eq(WorkOrderEntity::getSiteId, id)
                            .ne(WorkOrderEntity::getStatus, WorkOrderStatus.COMPLETED));
            if (openWorkOrders != null && openWorkOrders > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "该厂区仍有未结束工单，不能停用");
            }
        }
        //6.比较规范化后的配置，只保存固定变化字段名，禁止把名称地址正文送入审计。
        List<String> changedFields = new ArrayList<>();
        if (!Objects.equals(site.getName(), trName)) changedFields.add("name");
        if (!Objects.equals(site.getAddress(), address)) changedFields.add("address");
        if (!Objects.equals(site.getEnabled(), targetEnabled)) changedFields.add("enabled");
        //7.只更新允许字段，条件同时限定企业和厂区
        LambdaUpdateWrapper<SiteEntity> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(SiteEntity::getTenantId, tenantId)
                .eq(SiteEntity::getId, id)
                .set(SiteEntity::getName, trName)
                .set(SiteEntity::getAddress, address)
                .set(SiteEntity::getEnabled, targetEnabled);
        int changedRows = siteMapper.update(null, updateWrapper);
        //8.有实际变化时必须影响一行，避免未更新却追加成功审计。
        if (!changedFields.isEmpty() && changedRows != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "厂区资料已变化，请刷新后重试");
        }
        //9.回查数据库维护的审计时间并返回
        SiteEntity saved = siteMapper.selectOne(
                new LambdaQueryWrapper<SiteEntity>()
                        .eq(SiteEntity::getTenantId, tenantId)
                        .eq(SiteEntity::getId, id));
        if (saved == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "厂区不存在");
        }
        SiteVO vo = new SiteVO();
        BeanUtils.copyProperties(saved, vo);
        //10.相同配置重试不生成重复成功事件；审计失败与资料修改一起回滚。
        if (!changedFields.isEmpty()) {
            auditEventService.appendSuccess(new AuditAppendCommand(tenantId, currentAccessService.requireEnabledUserId(),
                    AuditAction.SITE_UPDATED, AuditObjectType.SITE, id,
                    Map.of("changedFields", List.copyOf(changedFields), "beforeEnabled", site.getEnabled(),
                            "afterEnabled", targetEnabled), null, null));
        }
        return vo;
    }
}
