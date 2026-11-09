package com.inventory.service.impl;

import com.auth.service.CurrentAccessService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.equipops.domain.dto.inventory.StockIssueCreateDto;
import com.equipops.domain.entity.InventoryTransactionEntity;
import com.equipops.domain.entity.WorkOrderEntity;
import com.equipops.domain.enums.WorkOrderStatus;
import com.equipops.domain.vo.InventoryTransactionVO;
import com.equipops.domain.vo.StockInWarehouseCheckVO;
import com.inventory.mapper.InventoryTransactionMapper;
import com.inventory.mapper.StockIssueMapper;
import com.inventory.service.StockIssueService;
import com.workorder.mapper.WorkOrderMapper;
import jakarta.transaction.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.equipops.domain.enums.InventoryTransactionType;
import com.equipops.domain.enums.InventoryReferenceType;
import org.springframework.dao.DuplicateKeyException;

@Service
public class StockIssueServiceImpl implements StockIssueService {
    @Autowired
    private StockIssueMapper stockIssueMapper;
    @Autowired
    private CurrentAccessService currentAccessService;
    //检查库存流水
    @Autowired
    private InventoryTransactionMapper inventoryTransactionMapper;
    @Autowired
    private WorkOrderMapper workOrderMapper;

    /**
     * 领料接口的业务是：负责人为维修工单领用备件，同时扣仓库库存、增加工单已领数量，并记录出库流水。
     */
    @Override
    @Transactional(rollbackOn = Exception.class)
    public InventoryTransactionVO issue(String requestId, StockIssueCreateDto request) {
        //1. 获取可信身份。
        Long tenantId = currentAccessService.requireActiveTenantId();
        Long userId = currentAccessService.requireEnabledUserId();
        //2. 校验参数。
        /**
         * 请求号符合现有幂等规则；
         *    工单、仓库、备件 ID 都是正数；
         *    数量大于零，精度和备注长度合法。
         */
        if (!StringUtils.hasText(requestId) || request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求号和领料参数不能为空!");
        }
        if (!requestId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "业务请求号需为1～64位字母、数字、下划线或短横线");
        }
        //获取前端备件,仓库，工单ID,
        if (request.getSparePartId() == null || request.getWarehouseId() == null || request.getWorkOrderId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工单、仓库、备件 ID不能为空！");
        }
        if (request.getSparePartId() <= 0 || request.getWarehouseId() <= 0 || request.getWorkOrderId() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工单、仓库、备件 ID 必须大于0！");
        }
        if (request.getQuantity() == null || request.getQuantity().signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "领用数量必须大于0");
        }
        // 与 DTO 的精度和长度限制一致，直接调用业务层也不允许数据库静默舍入。
        if (request.getQuantity().scale() > 3
                || request.getQuantity().precision() - request.getQuantity().scale() > 11) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "领料数量最多11位整数和3位小数");
        }
        if (request.getNote() != null && request.getNote().length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "备注不能超过500个字符");
        }
        //3. 检查是否为已成功重复请求。
        /** 按 tenantId + requestId 查询流水。
         *    如果存在：比较操作人、工单、仓库、备件、数量、备注，并确认是 OUTBOUND、WORK_ORDER 类型。
         *        全部一致 → 返回原流水，不再扣库存。不一致 → 返回409。
         */
        List<InventoryTransactionEntity> exist = inventoryTransactionMapper.selectList(
                new LambdaQueryWrapper<InventoryTransactionEntity>()
                        //条件1：属于当前企业（多租户隔离）
                        .eq(InventoryTransactionEntity::getTenantId, tenantId)
                        // 条件2：请求流水号是当前前端传来的 requestId
                        .eq(InventoryTransactionEntity::getRequestId, requestId)
        );
        if (!exist.isEmpty()) {
            //从集合里把第一条实体对象拿出来，与并发重试共用同一套判断。
            return replayIssue(exist.get(0), userId, request);
        }
        //4.锁定并校验本次领料的资源。
        /**
         *    与入库保持同类资源的加锁顺序，再锁定目标工单。仓库、厂区和备件属于当前企业且已启用；
         *    单属于当前企业；工单状态是 IN_PROGRESS；当前用户是工单负责人
         */
        //查询仓库及所属厂区。
        StockInWarehouseCheckVO warehouse = stockIssueMapper.lockWarehouseForIssue(tenantId, request.getWarehouseId());
        //  仓库不存在，拒绝。
        if (warehouse == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "仓库不存在");
        }
        //  仓库已停用，拒绝。
        if (!Boolean.TRUE.equals(warehouse.getWarehouseEnabled())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "仓库已停用");
        }
        // 所属厂区不存在或已停用，拒绝。
        if (warehouse.getSiteId() == null || !Boolean.TRUE.equals(warehouse.getSiteEnabled())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "拒绝使用");
        }
        //调用 Mapper，按 tenantId + sparePartId 查询并锁定备件
        Boolean sparePartEnabled = stockIssueMapper.lockSparePartEnabled(tenantId, request.getSparePartId());
        if (sparePartEnabled == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "备件不存在");
        }
        if (!Boolean.TRUE.equals(sparePartEnabled)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "备件已停用");
        }
        //通过后再检查工单
        // 参数顺序：当前企业 ID、请求中的工单 ID。
        WorkOrderEntity workOrder = workOrderMapper.selectTenantAnduserId(tenantId, request.getWorkOrderId());// 查询并锁定当前企业的工单。
        // 工单不存在，或不属于当前企业。
        if (workOrder == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "工单不存在");
        }
        //  只有维修中的工单才能领料。
        if (workOrder.getStatus() != WorkOrderStatus.IN_PROGRESS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "工单不是维修中状态，不能领料");
        }
        //  只有当前工单负责人才能领料。
        if (!Objects.equals(workOrder.getAssigneeUserId(), userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有工单负责人才能领料");
        }
        //5. 组装出库流水：
        /**
         * . 插入领料流水，取得请求号的执行资格。
         *    类型固定 OUTBOUND；关联类型固定 WORK_ORDER；
         *    记录工单、仓库、备件、数量和真实操作人。并发请求号冲突时，重查并判断能否返回原结果。
         */

        // 以下第5～7步由 Codex 补齐；上方第1～4步保留手写结构。
        InventoryTransactionEntity log = new InventoryTransactionEntity();
        log.setTenantId(tenantId);
        log.setCreatedBy(userId);
        log.setRequestId(requestId);
        log.setTransactionNo("OUT-" + UUID.randomUUID().toString().replace("-", ""));
        log.setWarehouseId(request.getWarehouseId());
        log.setSparePartId(request.getSparePartId());
        log.setWorkOrderId(request.getWorkOrderId());
        log.setTransactionType(InventoryTransactionType.OUTBOUND);
        log.setReferenceType(InventoryReferenceType.WORK_ORDER);
        // 出库方向用 OUTBOUND 表示，流水中的领用数量仍保存正数。
        log.setQuantity(request.getQuantity());
        log.setNote(request.getNote());
        try {
            // 先占用企业内唯一请求号，成功后才允许扣库存。
            if (stockIssueMapper.insertStockIssue(log) != 1 || log.getId() == null) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "领料流水保存失败");
            }
        } catch (DuplicateKeyException exception) {
            // 第3步的普通查询可能没看见另一个事务刚提交的记录，因此这里用当前读。
            InventoryTransactionEntity previous = stockIssueMapper.findByRequestForUpdate(tenantId, requestId);
            if (previous == null) {
                // 例如流水编号碰撞；不能将其他唯一键冲突当作本次领料成功。
                throw new ResponseStatusException(HttpStatus.CONFLICT, "领料流水编号冲突，请使用原请求号重试");
            }
            return replayIssue(previous, userId, request);
        }

        //6. 原子扣减仓库库存。
        // SQL 同时限定企业、仓库、备件和足额条件；不先读余额再把旧值写回。
        int stockRows = stockIssueMapper.decreaseStock(
                tenantId, request.getWarehouseId(), request.getSparePartId(), request.getQuantity());
        if (stockRows != 1) {
            // 没有余额行或余额不足都拒绝；抛异常让前面的流水插入一并回滚。
            throw new ResponseStatusException(HttpStatus.CONFLICT, "仓库库存不足或尚未建账，不能领料");
        }

        //7. 累计工单已领数量，并返回保存后的出库流水。
        // 首次领用建立用料行；已有行不覆盖计划量、已领量和退回量。
        stockIssueMapper.ensureWorkOrderPartRow(tenantId, request.getWorkOrderId(), request.getSparePartId());
        int partRows = stockIssueMapper.increaseIssuedQuantity(
                tenantId, request.getWorkOrderId(), request.getSparePartId(), request.getQuantity());
        if (partRows != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "工单已领数量超过上限或用料状态已变化");
        }
        // 重查数据库生成的创建时间；不能直接用未刷新时间的 log 当作最终结果。
        InventoryTransactionEntity saved = stockIssueMapper.findById(tenantId, log.getId());
        if (saved == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "领料流水读取失败");
        }
        return toIssueVO(saved);
    }

    /** 普通重试和并发重试共用：同一人、同一业务、相同领料内容才返回原流水。 */
    /**
    这个请求号以前用过了，现在的请求是不是原来那次领料的重试？
     */
    private InventoryTransactionVO replayIssue(InventoryTransactionEntity log, Long userId,
                                               StockIssueCreateDto request) {
        // 调用前的查询已限定 tenantId + requestId，此处只比较操作人、类型和内容。
        boolean sameUser = Objects.equals(log.getCreatedBy(), userId);
        boolean sameBusiness = log.getTransactionType() == InventoryTransactionType.OUTBOUND
                && log.getReferenceType() == InventoryReferenceType.WORK_ORDER;
        boolean sameContent = Objects.equals(log.getWorkOrderId(), request.getWorkOrderId())
                && Objects.equals(log.getWarehouseId(), request.getWarehouseId())
                && Objects.equals(log.getSparePartId(), request.getSparePartId())
                && Objects.equals(log.getNote(), request.getNote())
                && log.getQuantity() != null
                && log.getQuantity().compareTo(request.getQuantity()) == 0;
        if (!sameUser || !sameBusiness || !sameContent) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请求号已用于其他操作或不同领料内容");
        }
        return toIssueVO(log);
    }

    /** 将已保存的数据库流水转换为接口返回对象。 */
    private InventoryTransactionVO toIssueVO(InventoryTransactionEntity entity) {
        InventoryTransactionVO vo = new InventoryTransactionVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}
