package com.tenant.service.impl;

import com.auth.service.CurrentAccessService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.equipops.domain.dto.tenant.WarehouseUpsertDto;
import com.equipops.domain.entity.SiteEntity;
import com.equipops.domain.entity.WarehouseEntity;
import com.equipops.domain.vo.WarehouseVO;
import com.tenant.mapper.SiteMapper;
import com.tenant.mapper.WarehouseMapper;
import com.tenant.service.WarehouseService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class WarehouseServiceImpl implements WarehouseService {
    @Autowired
    private CurrentAccessService currentAccessService;
    @Autowired
    private WarehouseMapper warehouseMapper;
    @Autowired
    private SiteMapper siteMapper;   // 第 4 步校验厂区要用

    @Override
    public List<WarehouseVO> getDefaultWarehouses() {

        /**
         * 方法：getDefaultWarehouses()
         * 1. 调用 requireActiveTenantId()
         *    获取经过验证的当前企业 ID
         *    未登录 → 返回错误
         *    尚未选择企业 → 返回错误
         *    企业停用或成员关系失效 → 返回错误
         * 2. 查询 warehouses
         *    条件：
         *      tenant_id = 当前企业 ID
         *      is_default = true
         *      enabled = true
         * 3. 判断是否找到仓库
         *    没找到 → 提示“当前企业的默认仓库未初始化或未启用”
         * 4. 根据仓库的 site_id 查询所属厂区
         *    检查：
         *      厂区存在
         *      厂区属于当前企业
         *      厂区处于启用状态
         *    不满足 → 提示“默认仓库所属厂区不可用”
         * 5. 将 WarehouseEntity 转换为 WarehouseVO
         * 6. 将 VO 放入列表并返回
         *    保持当前 List<WarehouseVO> 的接口约定
         *    | 字段 | 表示什么 |
         * |---|---|
         * | `warehouse.getId()` | 仓库自身 ID |
         * | `warehouse.getTenantId()` | 仓库属于哪家企业 |
         * | `warehouse.getSiteId()` | 仓库属于哪个厂区 |
         */
        //1. 调用 requireActiveTenantId(),验证当前企业和成员关系后返回企业 ID；没有选择企业返回 409。
        Long tenantId = currentAccessService.requireActiveTenantId();
        //2.查询 warehouses企业的默认仓库
        LambdaQueryWrapper<WarehouseEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WarehouseEntity::getTenantId, tenantId)//租户ID匹配
                .eq(WarehouseEntity::getIsDefault, true)//是默认仓库,已启用
                .eq(WarehouseEntity::getEnabled, true);//是启用状态 Long tenantId = currentAccessService.requireActiveTenantId();

        //去数据库里，把符合 wrapper 里面所有条件的仓库，全部查出来，装进一个 List 集合里返回
        List<WarehouseEntity> warehouses = warehouseMapper.selectList(wrapper);

        if (warehouses.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前企业的默认仓库未初始化或未启用");
        }
        //4. 根据仓库的 site_id 查询所属的厂区
        WarehouseEntity warehouse = warehouses.get(0);//获取仓库第一个值
        //根据仓库ID查询仓库所属的厂区的ID
        SiteEntity site = siteMapper.selectById(warehouse.getSiteId());

        if (site == null) {
            throw new IllegalArgumentException("厂区不存在");
        }
        // 厂区属于当前企业
        if (!tenantId.equals(site.getTenantId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "所属厂区不属于当前企业");
        }
        if (!Boolean.TRUE.equals(site.getEnabled())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "所属厂区未启用");
        }
        // 6.将 VO 放入列表并返回 使用 Stream 直接一步到位：遍历 -> 转换 -> 收集成新列表 -> 返回
        return warehouses.stream()
                .map(entity -> {
                    WarehouseVO vo = new WarehouseVO();
                    BeanUtils.copyProperties(entity, vo);//5. 将 WarehouseEntity 转换为 WarehouseVO
                    return vo;
                })
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WarehouseVO createWarehouse(WarehouseUpsertDto request) {
        //1.取可信企业
        Long tenantId = currentAccessService.requireTenantAdminTenantId();
        //2. 校验并规范化参数
        String wareCode = request.getWarehouseCode();
        Long siteId = request.getSiteId();
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求参数不能为空");
        }
        if (siteId == null ||siteId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "厂区 ID 无效");
        }
        // // 编号与名称去空格判空
        String trWareCode = wareCode.trim();
        String trName = request.getName().trim();
        if (trWareCode.isEmpty() && trName.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仓库编号和仓库名字不能为空");
        }
        // location 空串转 null
        String location = request.getLocation();
        if (location != null) {
            location = location.trim();
            if (location.isEmpty()) {
                location = null;
            }
        }
        // enabled 为空按 true 处理
        Boolean enabled = request.getEnabled() != null ? request.getEnabled() : true;
        // 3. 校验厂区归属及状态
        SiteEntity site = siteMapper.selectOne(new LambdaQueryWrapper<SiteEntity>()
                .eq(SiteEntity::getTenantId, tenantId)
                .eq(SiteEntity::getId, siteId));
        if (site == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "所属厂区不存在");
        }
        if (!Boolean.TRUE.equals(site.getEnabled())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "所属厂区未启用");
        }
        //4. 仓库编号查重
        Long count = warehouseMapper.selectCount(new LambdaQueryWrapper<WarehouseEntity>()
                .eq(WarehouseEntity::getTenantId, tenantId)
                .eq(WarehouseEntity::getWarehouseCode, wareCode));
        if (count > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "仓库编号已存在");
        }
        //5.构造实体
        WarehouseEntity warehouse = WarehouseEntity.builder()
                .tenantId(tenantId)
                .siteId(siteId)
                .warehouseCode(wareCode)
                .name(request.getName())
                .location(location)
                .enabled(enabled)
                .isDefault(false)
                .build();
        // 6. 写入并处理并发异常
        try {
            int rows = warehouseMapper.insert(warehouse);
            if (rows != 1) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "仓库登记失败");
            }
        } catch (DuplicateKeyException e) {
            // 拦截高并发下的重复编号插入
            throw new ResponseStatusException(HttpStatus.CONFLICT, "仓库编号已存在");
        }
        //7. 回查并组装返回
        /**
         * 为了验证“数据真的成功落地且能被正常读出来”，防止出现“写入半成品”的假成功状态。
         */
        WarehouseEntity saved = warehouseMapper.selectOne(
                new LambdaQueryWrapper<WarehouseEntity>()
                .eq(WarehouseEntity::getTenantId, tenantId)
                .eq(WarehouseEntity::getId, warehouse.getId()));
        if (saved == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "仓库信息读取失败");
        }

        WarehouseVO vo = new WarehouseVO();
        BeanUtils.copyProperties(saved, vo);
        return vo;
    }
}
