package com.equipment.service.impl;

import com.auth.scope.SiteWriteAccessService;
import com.auth.service.CurrentAccessService;
import com.equipment.mapper.EquipmentRuntimeMaintenanceMapper;
import com.equipment.mapper.EquipmentRuntimeReadingMapper;
import com.equipment.service.EquipmentService;
import com.equipops.domain.vo.EquipmentRuntimeMaintenanceVO;
import com.equipops.domain.vo.EquipmentVO;
import com.workorder.service.WorkOrderService;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class EquipmentRuntimeMaintenanceService {
    private final EquipmentService equipment;
    private final EquipmentRuntimeReadingMapper readings;
    private final EquipmentRuntimeMaintenanceMapper plans;
    private final SiteWriteAccessService siteWriteAccess;
    private final CurrentAccessService access;
    private final WorkOrderService orders;

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public EquipmentRuntimeMaintenanceVO current(Long equipmentId) {
        // 设备及阈值查询均受企业和厂区范围限制。
        var asset = equipment.getEquipmentDetail(equipmentId);
        var plan = plans.find(asset.getTenantId(), asset.getSiteId(), equipmentId);
        return plan == null ? new EquipmentRuntimeMaintenanceVO() : plan;
    }

    /** 1.核验阈值及管理员；2.锁定授权和设备；3.保存阈值；4.按最新读数判断是否触发。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EquipmentRuntimeMaintenanceVO configure(Long equipmentId, BigDecimal hours, long expectedVersion) {
        if (hours == null || hours.signum() <= 0 || hours.scale() > 2
                || hours.compareTo(new BigDecimal("1000000000000")) >= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "维护阈值须为正数，最多12位整数和2位小数");
        }
        // 先按当前管理员身份校验，再在厂区写锁内复查该角色。
        access.requireTenantAdminTenantId();
        var asset = equipment.getEquipmentDetail(equipmentId);
        siteWriteAccess.lockSiteAsAdmin(asset.getSiteId());
        // 与抄表共享设备行锁，避免配置和读数同时到达时漏建工单。
        if (readings.lockEquipment(asset.getTenantId(), asset.getSiteId(), equipmentId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在");
        }
        var plan = plans.find(asset.getTenantId(), asset.getSiteId(), equipmentId);
        if (plan != null && hours.compareTo(plan.getThresholdHours()) == 0) {
            return plan;
        }
        // 当前配置已被其他操作更新时，拒绝用过期表单覆盖。
        if (expectedVersion != (plan == null ? 0 : plan.getVersion())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "维护阈值已更新，请刷新后重试");
        }
        var latest = readings.recent(asset.getTenantId(), asset.getSiteId(), equipmentId, 1);
        if (plan != null && !latest.isEmpty()
                && hours.compareTo(latest.get(0).getHours()) <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "下一次维护阈值必须大于最新累计读数");
        }
        // 更新待触发阈值。已生成的工单保留在设备工单履历。
        plans.save(asset.getTenantId(), asset.getSiteId(), equipmentId, hours);
        var saved = plans.find(asset.getTenantId(), asset.getSiteId(), equipmentId);
        if (!latest.isEmpty()) {
            generateOrderIfDue(asset, equipmentId, latest.get(0).getHours(), saved);
        }
        return saved;
    }

    /** 读数写入已持有设备行锁。判断到期、创建工单及关联均加入该事务。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onReading(EquipmentVO asset, Long equipmentId, BigDecimal hours) {
        var plan = plans.find(asset.getTenantId(), asset.getSiteId(), equipmentId);
        generateOrderIfDue(asset, equipmentId, hours, plan);
    }

    private void generateOrderIfDue(EquipmentVO asset, Long equipmentId, BigDecimal hours,
            EquipmentRuntimeMaintenanceVO plan) {
        // 未到期和已建单的阈值都保留原状态。
        if (plan == null || plan.getWorkOrderId() != null || hours.compareTo(plan.getThresholdHours()) < 0) {
            return;
        }
        // 保存触发依据，后续调整阈值时仍可通过历史工单核对。
        String description = "维护阈值 " + plan.getThresholdHours().toPlainString()
                + " h，触发读数 " + hours.toPlainString() + " h";
        var order = orders.createRuntimeMaintenanceOrder(equipmentId, description);
        if (plans.linkOrder(asset.getTenantId(), asset.getSiteId(), equipmentId,
                plan.getThresholdHours(), order.getId()) != 1) {
            throw new IllegalStateException("维护工单关联失败");
        }
        plan.setWorkOrderId(order.getId());
    }
}
