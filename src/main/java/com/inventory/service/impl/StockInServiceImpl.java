package com.inventory.service.impl;

import com.auth.model.TenantAccess;
import com.auth.service.CurrentAccessService;
import com.equipops.domain.dto.inventory.StockInCreateDto;
import com.equipops.domain.vo.InventoryTransactionVO;
import com.inventory.mapper.StockInMapper;
import com.equipops.domain.entity.InventoryTransactionEntity;
import com.equipops.domain.enums.InventoryTransactionType;
import com.equipops.domain.enums.InventoryReferenceType;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import java.util.Objects;
import java.util.UUID;
import com.equipops.domain.vo.StockInWarehouseCheckVO;
import com.inventory.service.StockInService;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

@Service
public class StockInServiceImpl implements StockInService {
    @Autowired
    //可获取当前企业、当前用户和实时企业角色
    private CurrentAccessService currentAccessService;
    @Autowired
    // XML 联查完成以下资源校验，保留原用途说明。
    //仓库表,确认目标仓库属于当前企业、没有停用，并取得它所属的厂区 ID
    //厂区表,根据仓库的 siteId，确认所属厂区属于当前企业且已启用
    //备件表,确认要入库的备件属于当前企业，并且没有停用
    private StockInMapper stockInMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InventoryTransactionVO stocktoIn(String requestId, StockInCreateDto request) {
        // 1. 获取当前企业、当前用户和实时企业角色。
        TenantAccess access = currentAccessService.requireTenantAccess();
        Long tenantId = access.getTenant().getId();
        Long userId = access.getUserId();
        //2. 校验请求号、仓库 ID、备件 ID、数量等参数。
        if (!StringUtils.hasText(requestId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "业务请求号不能为空");
        }
        if (request == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "入库参数不能为空");
        }
        //获取仓库Id
        Long warehouseId = request.getWarehouseId();
        Long sparePartId = request.getSparePartId();
        BigDecimal quantity = request.getQuantity();

        if (warehouseId == null || warehouseId <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "仓库 ID 必须大于0");
        }
        if (sparePartId == null || sparePartId <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "备件 ID 必须大于0");
        }
        if (quantity == null || quantity.signum() <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "入库数量必须大于0");
        }

        // precision 是总有效位数，scale 是小数位数。
        // 与 DTO 和数据库 DECIMAL(14,3) 对齐：最多11位整数、3位小数。
        if (quantity.scale() > 3
                || quantity.precision() - quantity.scale() > 11) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "入库数量最多11位整数和3位小数");
        }

        if (request.getReferenceText() != null
                && request.getReferenceText().length() > 160) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "关联单据说明不能超过160个字符");
        }

        if (request.getNote() != null
                && request.getNote().length() > 500) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "备注不能超过500个字符");
        }
        // 请求号是客户端的业务标识，不做大小写转换或静默截断，与 V6 的唯一键一致。
        if (!requestId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "业务请求号须为1到64位字母、数字、横线或下划线");
        }

        //3. 校验入库权限及仓库、备件的归属和启用状态。
        // 当前采用已有备件管理的企业管理员权限；每次调用重新验证，撤权后不能继续入库。
        if (!access.getRoleCodes().contains("TENANT_ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有当前企业管理员可以入库");
        }

        //4. 处理幂等：相同请求只能入库一次，不同内容不能复用请求号。
        // 成功重试先返回原流水，不再要求历史仓库或备件仍处于启用状态。
        InventoryTransactionEntity previous = stockInMapper.findByRequest(tenantId, requestId);
        if (previous != null) {
            return replay(previous, tenantId, userId, requestId, request);
        }

        // 新请求才锁定并检查资源；仓库/厂区之后再锁备件，保持统一顺序至事务结束。
        // XML 同时限定当前企业，未知和其他企业仓库统一按不存在处理。
        StockInWarehouseCheckVO warehouseCheck =
                stockInMapper.lockWarehouseForStockIn(tenantId, warehouseId);
        if (warehouseCheck == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "仓库不存在");
        }
        if (!Boolean.TRUE.equals(warehouseCheck.getWarehouseEnabled())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "仓库已停用，不能入库");
        }
        // LEFT JOIN 未找到同企业厂区时 siteId 为空；停用厂区也不能接收入库。
        if (warehouseCheck.getSiteId() == null
                || !Boolean.TRUE.equals(warehouseCheck.getSiteEnabled())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "仓库所属厂区不可用");
        }

        // Boolean 为 null 表示没有当前企业备件记录，false 表示存在但已停用。
        Boolean partEnabled = stockInMapper.lockSparePartEnabled(tenantId, sparePartId);
        if (partEnabled == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "备件不存在");
        }
        if (!Boolean.TRUE.equals(partEnabled)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "备件已停用，不能入库");
        }

        //6. 保存入库流水。
        // 此步先于第5步执行：以企业内唯一请求号取得执行资格，再增加库存。
        // 插入、建账、加量都在当前事务中，后续失败会一并回滚，不留下半笔流水。
        InventoryTransactionEntity log = new InventoryTransactionEntity();
        log.setTenantId(tenantId);
        log.setWarehouseId(warehouseId);
        log.setSparePartId(sparePartId);
        log.setRequestId(requestId);
        log.setTransactionNo("IN-" + UUID.randomUUID().toString().replace("-", ""));
        log.setTransactionType(InventoryTransactionType.INBOUND);
        log.setReferenceType(InventoryReferenceType.MANUAL);
        log.setQuantity(quantity.setScale(3));
        log.setReferenceText(request.getReferenceText());
        log.setNote(request.getNote());
        log.setCreatedBy(userId);
        try {
            if (stockInMapper.insertStockIn(log) != 1 || log.getId() == null) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "入库流水保存失败");
            }
        } catch (DuplicateKeyException exception) {
            // 并发相同请求会等待先提交的事务；冲突后用 FOR UPDATE 当前读获取最新结果。
            InventoryTransactionEntity concurrent = stockInMapper.findByRequestForUpdate(tenantId, requestId);
            if (concurrent == null) {
                // 也可能是流水编号等其他唯一键冲突，不能当成本请求已成功。
                throw new ResponseStatusException(HttpStatus.CONFLICT, "入库请求发生冲突，请重试");
            }
            return replay(concurrent, tenantId, userId, requestId, request);
        }

        //5. 原子增加仓库余额。
        // 首次建账从零开始，已有记录不覆盖阈值；不读取或修改目录中的旧库存。
        stockInMapper.ensureStockRow(tenantId, warehouseId, sparePartId);
        if (stockInMapper.increaseStock(tenantId, warehouseId, sparePartId, log.getQuantity()) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "入库数量超过库存上限或库存状态已变化");
        }

        //7. 返回保存后的流水；任一步失败，由事务整体回滚。
        InventoryTransactionEntity saved = stockInMapper.findById(tenantId, log.getId());
        if (saved == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "入库流水读取失败");
        }
        return toVO(saved);
    }

    /** 相同请求号只能重放原操作人的相同入库；数值比较忽略 BigDecimal 的小数表示差异。 */
    private InventoryTransactionVO replay(InventoryTransactionEntity previous, Long tenantId, Long userId,
                                         String requestId, StockInCreateDto request) {
        boolean same = Objects.equals(previous.getTenantId(), tenantId)
                && Objects.equals(previous.getRequestId(), requestId)
                && Objects.equals(previous.getCreatedBy(), userId)
                && previous.getTransactionType() == InventoryTransactionType.INBOUND
                && previous.getReferenceType() == InventoryReferenceType.MANUAL
                && previous.getWorkOrderId() == null
                && Objects.equals(previous.getWarehouseId(), request.getWarehouseId())
                && Objects.equals(previous.getSparePartId(), request.getSparePartId())
                && previous.getQuantity() != null
                && previous.getQuantity().compareTo(request.getQuantity()) == 0
                && Objects.equals(previous.getReferenceText(), request.getReferenceText())
                && Objects.equals(previous.getNote(), request.getNote());
        if (!same) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "业务请求号已被其他操作或不同入库内容使用");
        }
        return toVO(previous);
    }

    /** 返回数据库流水字段，不把库存余额当成本次入库数量。 */
    private InventoryTransactionVO toVO(InventoryTransactionEntity entity) {
        InventoryTransactionVO result = new InventoryTransactionVO();
        BeanUtils.copyProperties(entity, result);
        return result;
    }
}
