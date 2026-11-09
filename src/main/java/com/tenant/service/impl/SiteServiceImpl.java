package com.tenant.service.impl;

import com.auth.service.CurrentAccessService;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class SiteServiceImpl implements SiteService {
    @Autowired
    private CurrentAccessService currentAccessService;

    @Autowired
    private SiteMapper siteMapper;

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
        //1.获取当前身份与当前企业
        Long tenantId = currentAccessService.requireActiveTenantId();
        //2.校验并规范化参数
        //如果request == null 就拒绝；对siteCode、name 分别去掉首尾空格并判空，空则返回 400；
        if(request ==null){
            throw new ResponseStatusException(HttpStatus.CONFLICT,"请求拒绝");
        }
        String trSiteCode= request.getSiteCode().trim();
        String trName= request.getName().trim();
        if(trSiteCode.isEmpty() && trName.isEmpty()){
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "厂区编号和厂区名字不能为空");
        }
        // 对`address` 去掉首尾空格，空串或全空白统一转成`null` ；
        String address = request.getAddress();
        if (address != null) {
            address = address.trim();
            if (address.isEmpty()) {
                address = null;
            }
        }
        // 对enabled 为null 时按true 处理。
        Boolean enabled = request.getEnabled() != null ? request.getEnabled() : true;
        //3.厂区编号查重,如果大于 0 就返回 409厂区编号已存在
        Long siteCount =siteMapper.selectCount(
                new LambdaQueryWrapper<SiteEntity>()
                .eq(SiteEntity::getTenantId, tenantId)
                .eq(SiteEntity::getSiteCode, trSiteCode)
        );
        if(siteCount>0){
            throw new ResponseStatusException(HttpStatus.CONFLICT, "厂区编号已存在");
        }
        //4.构造实体
        SiteEntity site = SiteEntity.builder()
                .tenantId(tenantId)
                .siteCode(trSiteCode)
                .name(trName)
                .address(address)
                .enabled(enabled)
                .isDefault(false)
                .build();
        //5.数据返回给前端
        SiteVO siteVO = new SiteVO();
        BeanUtils.copyProperties(site, siteVO);
        return siteVO;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SiteVO updateSite(Long id, SiteUpsertDto request) {
        //1.获取当前操作者与企业
        Long tenantId = currentAccessService.requireActiveTenantId();
        Long userId = currentAccessService.requireEnabledUserId();
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
        //6.只更新允许字段，条件同时限定企业和厂区
        LambdaUpdateWrapper<SiteEntity> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(SiteEntity::getTenantId, tenantId)
                .eq(SiteEntity::getId, id)
                .set(SiteEntity::getName, trName)
                .set(SiteEntity::getAddress, address)
                .set(SiteEntity::getEnabled, targetEnabled);
        siteMapper.update(null, updateWrapper);
        //7.回查数据库维护的审计时间并返回
        SiteEntity saved = siteMapper.selectOne(
                new LambdaQueryWrapper<SiteEntity>()
                        .eq(SiteEntity::getTenantId, tenantId)
                        .eq(SiteEntity::getId, id));
        if (saved == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "厂区不存在");
        }
        SiteVO vo = new SiteVO();
        BeanUtils.copyProperties(saved, vo);
        return vo;
    }
}
