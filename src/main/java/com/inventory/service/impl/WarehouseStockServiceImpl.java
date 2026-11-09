package com.inventory.service.impl;

import com.auth.service.CurrentAccessService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.equipops.domain.dto.inventory.WarehouseStockMinQuantityUpdateDto;
import com.equipops.domain.dto.inventory.WarehouseStockPageQueryDto;
import com.equipops.domain.entity.WarehouseStockEntity;
import com.equipops.domain.vo.WarehouseStockVO;
import com.inventory.mapper.WarehouseStockMapper;
import com.inventory.service.WarehouseStockService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

/**
 * 仓库余额查询实现，企业范围来自可信登录上下文。
 */

@Service
@RequiredArgsConstructor
public class WarehouseStockServiceImpl implements WarehouseStockService {
    /** 验证登录用户及当前企业成员资格。 */
    private final CurrentAccessService currentAccessService;
    /** 查询企业内仓库、备件和库存余额。 */
    private final WarehouseStockMapper warehouseStockMapper;

    /** 分页查询当前企业已建账余额，不读取旧目录库存。 */
    @Override
    @Transactional(readOnly = true)
    public IPage<WarehouseStockVO> getStockPage(WarehouseStockPageQueryDto query) {
        // 1. 验证当前用户的企业成员资格，企业范围只能来自服务端登录上下文。
        Long tenantId = currentAccessService.requireActiveTenantId();

        // 2. HTTP 层的 @Valid 校验请求；这里同时保护其他代码直接调用 Service 的情况。
        if (query == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "查询条件不能为空");
        }
        if (query.getWarehouseId() != null && query.getWarehouseId() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仓库 ID 必须大于0");
        }
        if (query.getSparePartId() != null && query.getSparePartId() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "备件 ID 必须大于0");
        }
        if (query.getKeyword() != null && query.getKeyword().length() > 160) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "关键字不能超过160个字符");
        }

        // 3. 提供筛选 ID 时先校验当前企业可见性；未知和其他企业 ID 都返回 404。
        // 停用不等于库存消失，所以这里不按 enabled 过滤仓库或备件。
        if (query.getWarehouseId() != null
                && warehouseStockMapper.countTenantWarehouse(tenantId, query.getWarehouseId()) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "仓库不存在");
        }
        if (query.getSparePartId() != null
                && warehouseStockMapper.countTenantSparePart(tenantId, query.getSparePartId()) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "备件不存在");
        }

        // 4. 空白关键字不筛选；编号或名称的 OR 条件由 XML 分组，不能绕过企业条件。
        String keyword = StringUtils.hasText(query.getKeyword()) ? query.getKeyword().strip() : null;

        // 5. 联查当前企业已建账余额，分页插件同时返回本页和总数，不在 Java 中切分页。
        // 没有余额记录时返回空页，不能把未核对的旧目录数量或假零当成仓库库存。
        Page<WarehouseStockVO> page = new Page<>(Math.max(1, query.getCurrent()),
                Math.max(1, Math.min(100, query.getSize())));
        return warehouseStockMapper.selectStockPage(page, tenantId, query, keyword);
    }

    /** 修改安全库存阈值并返回完整关联资料，失败时回滚阈值更新。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public WarehouseStockVO updateMinStockQuantity(Long id, WarehouseStockMinQuantityUpdateDto request) {
        // 1. 验证当前用户是当前企业管理员，取得企业ID。
        Long tenantId = currentAccessService.requireTenantAdminTenantId();
        // 2. 校验余额记录ID、请求和阈值。
        if (id == null || id <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "余额记录 ID 必须大于0");
        }
        if (request == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "请求不能为空");
        }
        //    阈值允许为0，不能为负，最多 11 位整数、3位小数。
        BigDecimal minStockQuantity = request.getMinStockQuantity();
        if (minStockQuantity == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "阈值不能为空");
        }
        if (minStockQuantity.signum() < 0) {// signum() 返回 -1(负数)、0(零)、1(正数)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "阈值不能为负数");
        }
        if (minStockQuantity.compareTo(
                new BigDecimal("99999999999.999")) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "阈值超出允许范围");
        }
        // 精度校验
        if (minStockQuantity.scale() > 3) {//scale() 是小数位数，precision() 是总有效位数
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "阈值最多保留3位小数");
        }
        // 3. 按“企业ID + 余额记录ID”查询。
        //    不存在或属于其他企业，统一返回404。查询出来的那条“库存余额记录
       WarehouseStockEntity WareStock = warehouseStockMapper.selectOne(
                new LambdaQueryWrapper<WarehouseStockEntity>()
                        .eq(WarehouseStockEntity::getTenantId, tenantId)
                        .eq(WarehouseStockEntity::getId, id)
        );
        if (WareStock==null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, " 不存在或属于其他企业");
        }
        // 4. 只更新 min_stock_quantity，不修改 quantity。
        //    阈值相同时也应正常返回成功。
        int updateRows = warehouseStockMapper.updateMinStockQuantity(tenantId, id, minStockQuantity);
        // 5. 重新查询余额VO，返回最新阈值和lowStock判断。
        // 直接联表返回完整VO，字段和lowStock计算口径与库存列表一致。
        WarehouseStockVO vo = warehouseStockMapper.selectStockDetail(tenantId, id);
        if (vo == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "余额记录不存在或关联资料不可用");
        }
        // 同值更新可能影响0行，回查值一致仍成功；异常结果不能包装成功。
        if (updateRows < 0 || updateRows > 1
                || vo.getMinStockQuantity().compareTo(minStockQuantity) != 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "安全库存阈值更新失败，请刷新后重试");
        }
        return vo;
    }
}
