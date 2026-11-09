package com.inventory.service.impl;

import com.auth.service.CurrentAccessService;
import com.equipops.domain.dto.inventory.StockReturnCreateDto;
import com.equipops.domain.entity.InventoryTransactionEntity;
import com.equipops.domain.entity.WorkOrderEntity;
import com.equipops.domain.enums.InventoryTransactionType;
import com.equipops.domain.enums.InventoryReferenceType;
import com.equipops.domain.enums.WorkOrderStatus;
import com.equipops.domain.vo.StockInWarehouseCheckVO;
import com.equipops.domain.vo.InventoryTransactionVO;
import com.inventory.mapper.StockReturnMapper;
import com.inventory.service.StockReturnService;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import java.math.BigDecimal;
import java.util.Objects;

/** 工单退料业务：关联原领料退回原仓库，库存、用料和流水同事务。 */
@Service
public class StockReturnServiceImpl implements StockReturnService {
    /** 获取可信的当前企业和登录用户。 */
    @Autowired
    private CurrentAccessService currentAccessService;
    /** 查询原领料、检查累计退回、更新库存与用料、保存退料流水。 */
    @Autowired
    private StockReturnMapper stockReturnMapper;
    /**
     * 按原领料流水退回原仓库；
     * */
    @Override
    @Transactional(rollbackOn = Exception.class)
    public InventoryTransactionVO returnStock(String requestId, StockReturnCreateDto request) {
        //1. 获取当前企业和用户，校验参数。
        Long tenantId = currentAccessService.requireActiveTenantId();
        Long userId = currentAccessService.requireEnabledUserId();
        if (!StringUtils.hasText(requestId) || request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求号和退料参数不能为空");
        }
        if (!requestId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "业务请求号需为1～64位字母、数字、下划线或短横线");
        }
        if (request.getIssueTransactionId() == null || request.getIssueTransactionId() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "原领料流水 ID 必须大于0");
        }
        if (request.getQuantity() == null || request.getQuantity().signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "退料数量必须大于0");
        }
        if (request.getQuantity().scale() > 3
                || request.getQuantity().precision() - request.getQuantity().scale() > 11) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "退料数量最多11位整数和3位小数");
        }
        if (request.getNote() != null && request.getNote().length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "备注不能超过500个字符");
        }
        //2. 检查是否为已成功的重复请求。
        InventoryTransactionEntity previous = stockReturnMapper.findByRequest(tenantId, requestId);
        if (previous != null) {
            return replayReturn(previous, userId, request);
        }
        //3. 查询原领料流水，确认属于当前企业且为工单出库。
        InventoryTransactionEntity originalIssue = stockReturnMapper.selectNowTenant(tenantId, request.getIssueTransactionId());
        if (originalIssue == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "原领料流水不存在");
        }
        validateOriginalIssue(originalIssue);

        //4. 锁定相关资源，检查权限及剩余可退数量。
        // 与入库、领料保持仓库及厂区→备件→工单的顺序，最后锁定原领料。
        StockInWarehouseCheckVO warehouse = stockReturnMapper.lockWarehouseForReturn(tenantId, originalIssue.getWarehouseId());
        if (warehouse == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "原领料仓库不存在");
        }
        if (!Boolean.TRUE.equals(warehouse.getWarehouseEnabled())
                || warehouse.getSiteId() == null || !Boolean.TRUE.equals(warehouse.getSiteEnabled())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "原仓库或所属厂区不可用");
        }
        Boolean partEnabled = stockReturnMapper.lockSparePartEnabled(tenantId, originalIssue.getSparePartId());
        if (partEnabled == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "原领料备件不存在");
        }
        if (!Boolean.TRUE.equals(partEnabled)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "原领料备件已停用");
        }
        WorkOrderEntity workOrder = stockReturnMapper.lockWorkOrderForReturn(tenantId, originalIssue.getWorkOrderId());
        if (workOrder == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "原领料工单不存在");
        }
        // 已确认：维修中、提交验收后、完成后均可退料；管理员不额外绕过负责人限制。
        if (workOrder.getStatus() != WorkOrderStatus.IN_PROGRESS
                && workOrder.getStatus() != WorkOrderStatus.AWAITING_ACCEPTANCE
                && workOrder.getStatus() != WorkOrderStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前工单状态不允许退料");
        }
        if (!Objects.equals(workOrder.getAssigneeUserId(), userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有当前工单负责人才能退料");
        }
        InventoryTransactionEntity lockedIssue = stockReturnMapper.lockIssueForReturn(tenantId, originalIssue.getId());
        if (lockedIssue == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "原领料流水不存在");
        }
        validateOriginalIssue(lockedIssue);
        if (!Objects.equals(lockedIssue.getWarehouseId(), originalIssue.getWarehouseId())
                || !Objects.equals(lockedIssue.getSparePartId(), originalIssue.getSparePartId())
                || !Objects.equals(lockedIssue.getWorkOrderId(), originalIssue.getWorkOrderId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "原领料关联信息已变化，请重试");
        }
        // 后续使用当前读的原领料；同一原流水的退回请求持有同一把锁直至事务结束。
        originalIssue = lockedIssue;

        //5. 写退料流水，关联原领料并占用请求号。
        // createReturnLog只准备SQL参数，不写库；真正插入发生在insertStockReturn。
        // XML用INSERT SELECT从原领料复制仓库、备件和工单，固定退料类型并生成流水编号。
        // 保留插入主键log.id，最后用它重查数据库里的完整退料结果。
        InventoryTransactionEntity log = createReturnLog(tenantId, userId, requestId, originalIssue, request);
        try {
            if (stockReturnMapper.insertStockReturn(log) != 1 || log.getId() == null) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "退料流水保存失败");
            }
        } catch (DuplicateKeyException exception) {
            // 唯一键冲突后必须当前读，普通快照可能看不到另一个事务刚提交的结果。
            previous = stockReturnMapper.findByRequestForUpdate(tenantId, requestId);
            if (previous == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "退料流水编号冲突，请使用原请求号重试");
            }
            return replayReturn(previous, userId, request);
        }
        // 占用请求号后再检查累计上限，使并发重复请求优先重放，不误报“已经退满”。
        // 使用锁定当前读，且包括本事务刚插入的这一笔，不依赖第2步建立的旧快照。
        BigDecimal totalReturned = stockReturnMapper.selectReturnedQuantitiesForUpdate(
                tenantId, originalIssue.getId()).stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalReturned.compareTo(originalIssue.getQuantity()) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "累计退回数量不能超过原领用数量");
        }

        //6. 原子增加原仓库库存，累计工单已退数量。
        // 原领料应已有余额记录；缺失时拒绝，不能假设零余额重新建账。
        if (stockReturnMapper.increaseStock(tenantId, originalIssue.getWarehouseId(),
                originalIssue.getSparePartId(), request.getQuantity()) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "原仓库余额记录缺失或退回后库存超过上限");
        }
        // 保留工单计划量和已领量，只累计已退量；累计不能超过工单已领量。
        if (stockReturnMapper.increaseReturnedQuantity(tenantId, originalIssue.getWorkOrderId(),
                originalIssue.getSparePartId(), request.getQuantity()) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "工单用料记录缺失或累计退回超过已领数量");
        }
        //7. 返回保存后的流水，任一步失败交由事务回滚。
        InventoryTransactionEntity saved = stockReturnMapper.findById(tenantId, log.getId());
        if (saved == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "退料流水读取失败");
        }
        return toVO(saved);
    }

    /**
     * 准备本次退料的SQL参数，不执行查询或写库。
     * tenantId、userId来自登录上下文；原领料已经过企业归属、类型及锁定检查。
     * 这里只传请求号、原领料ID、数量和备注；仓库、备件、工单从原领料复制，流水编号在SQL生成。
     */
    private InventoryTransactionEntity createReturnLog(Long tenantId, Long userId, String requestId,
                                                       InventoryTransactionEntity originalIssue,
                                                       StockReturnCreateDto request) {
        InventoryTransactionEntity log = new InventoryTransactionEntity();
        log.setTenantId(tenantId);
        log.setCreatedBy(userId);
        log.setRequestId(requestId);
        log.setIssueTransactionId(originalIssue.getId());
        log.setQuantity(request.getQuantity());
        log.setNote(request.getNote());
        return log;
    }

    /** 原记录必须是真实、完整的工单领料，不能把入库或历史归属缺失流水当作来源。 */
    private void validateOriginalIssue(InventoryTransactionEntity issue) {
        if (issue.getTransactionType() != InventoryTransactionType.OUTBOUND
                || issue.getReferenceType() != InventoryReferenceType.WORK_ORDER) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该流水不是工单领料流水");
        }
        if (issue.getWarehouseId() == null || issue.getSparePartId() == null || issue.getWorkOrderId() == null
                || issue.getQuantity() == null || issue.getQuantity().signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "原领料关联信息或数量不完整，不能退料");
        }
    }

    /**
     * 核对重复请求并返回原退料结果，不再插流水或增加库存。
     * 调用位置：第2步查到已有请求，或第5步并发插入触发唯一键冲突之后。
     * 调用前的SQL已限定当前企业和请求号，这里继续核对操作人、业务类型及退料内容。
     * 同一请求号改了数量、备注或原领料，属于冲突，不能当作成功重试。
     */
    private InventoryTransactionVO replayReturn(InventoryTransactionEntity log, Long userId, StockReturnCreateDto request) {
        // 同一个请求号也不能由另一名用户冒用；Objects.equals可安全处理空值。
        boolean sameUser = Objects.equals(log.getCreatedBy(), userId);
        // 退料是“工单入库”，普通入库或领料流水不能被误认为本次退料。
        boolean sameBusiness = log.getTransactionType() == InventoryTransactionType.INBOUND
                && log.getReferenceType() == InventoryReferenceType.WORK_ORDER;
        // 核对原领料、数量和备注；compareTo忽略2与2.000的表示差异，数值相等即返回0。
        // 备注可为空，用Objects.equals避免空指针；空字符串与null仍是不同内容。
        boolean sameContent = Objects.equals(log.getIssueTransactionId(), request.getIssueTransactionId())
                && log.getQuantity() != null && log.getQuantity().compareTo(request.getQuantity()) == 0
                && Objects.equals(log.getNote(), request.getNote());
        if (!sameUser || !sameBusiness || !sameContent) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请求号已用于其他操作或不同退料内容");
        }
        // 只转换原成功流水并返回，当前重试不再执行后面的库存和用料更新。
        return toVO(log);
    }

    /**
     * 将数据库实体Entity转换成对外返回的VO，不执行SQL，也不改变业务数据。
     * 正常退料传入保存后重查的实体，重复请求传入原成功流水，因此均能返回真实主键及时间。
     * Controller随后再用Result.success包装此VO，形成统一接口响应。
     */
    private InventoryTransactionVO toVO(InventoryTransactionEntity entity) {
        // 创建接口返回对象，避免直接把数据库实体作为响应契约。
        InventoryTransactionVO vo = new InventoryTransactionVO();
        // 复制名称和类型匹配的字段，例如id、issueTransactionId、quantity和createdAt。
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}

