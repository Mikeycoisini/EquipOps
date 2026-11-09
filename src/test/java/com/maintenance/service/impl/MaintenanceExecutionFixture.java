package com.maintenance.service.impl;

import com.auth.model.TenantAccess;
import com.auth.service.CurrentAccessService;
import com.equipops.domain.dto.maintenance.MaintenanceExecutionCreateDto;
import com.equipops.domain.entity.MaintenanceExecutionEntity;
import com.equipops.domain.entity.TenantEntity;
import com.equipops.domain.enums.MaintenancePlanType;
import com.equipops.domain.vo.MaintenanceExecutionVO;
import com.equipops.domain.vo.MaintenancePlanVO;
import com.maintenance.mapper.MaintenancePlanMapper;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** 执行业务的内存数据库边界；不模拟或声称证明真实锁和事务回滚。 */
class MaintenanceExecutionFixture {
    final MaintenancePlanVO plan = new MaintenancePlanVO();
    final MaintenanceExecutionCreateDto request = new MaintenanceExecutionCreateDto("  已完成巡检  ");
    final LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
    String key = "exec-1";
    boolean denied, missing, admin, missingId, missingDetail, lockConflict, revokeAfterLock;
    int planReads, inserts, advances, periods, insertRows = 1, advanceRows = 1;
    MaintenanceExecutionEntity previous, stored, duplicateWinner;
    MaintenanceExecutionFixture() {
        plan.setId(100L); plan.setTenantId(7L); plan.setEquipmentId(20L); plan.setSiteId(40L);
        plan.setName("每周巡检"); plan.setPlanType(MaintenancePlanType.INSPECTION); plan.setContent("检查异响");
        plan.setCycleDays(7); plan.setNextDueDate(today.minusDays(3)); plan.setAssigneeUserId(30L); plan.setEnabled(true);
        plan.setEquipmentAssetCode("EQ-20"); plan.setEquipmentName("空压机"); plan.setAssigneeName("维修员");
    }
    MaintenancePlanServerImpl service() {
        var tenant = new TenantEntity(); tenant.setId(7L);
        var access = (CurrentAccessService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{CurrentAccessService.class}, (p,m,a) -> {
            assertEquals("requireTenantAccess", m.getName());
            if (denied) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "成员无效");
            return new TenantAccess(30L, tenant, 80L, admin ? Set.of("TENANT_ADMIN") : Set.of());
        });
        var mapper = (MaintenancePlanMapper) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{MaintenancePlanMapper.class}, (p,m,a) -> {
            assertEquals(7L, m.getName().equals("insertExecution") ? ((MaintenanceExecutionEntity)a[0]).getTenantId() : a[0]);
            switch(m.getName()) {
                case "selectPlanDetailForUpdate":
                    assertEquals(100L,a[1]); planReads++;
                    if (lockConflict) throw new CannotAcquireLockException("private SQL details");
                    return missing ? null : plan;
                case "selectEnabledExecutorForUpdate":
                    assertEquals(30L,a[1]); return revokeAfterLock && planReads > 0 ? null : 30L;
                case "findExecutionByRequest": assertTrue(planReads > 0); assertEquals(key,a[1]); return previous;
                case "countExecutionForPeriod": assertEquals(100L,a[1]); assertEquals(plan.getNextDueDate(),a[2]); return periods;
                case "insertExecution":
                    inserts++;
                    if (duplicateWinner != null) { previous = duplicateWinner; throw new DuplicateKeyException("private SQL"); }
                    stored = new MaintenanceExecutionEntity(); BeanUtils.copyProperties(a[0],stored);
                    // 模拟INSERT SELECT从源表读取快照；Java入参只提供本次执行参数。
                    stored.setSiteId(plan.getSiteId()); stored.setEquipmentId(plan.getEquipmentId());
                    stored.setPlanName(plan.getName()); stored.setPlanType(plan.getPlanType());
                    stored.setPlanContent(plan.getContent()); stored.setCycleDays(plan.getCycleDays());
                    stored.setScheduledDate(plan.getNextDueDate());
                    stored.setEquipmentAssetCode(plan.getEquipmentAssetCode()); stored.setEquipmentName(plan.getEquipmentName());
                    stored.setExecutedByName(plan.getAssigneeName());
                    if (!missingId) { ((MaintenanceExecutionEntity)a[0]).setId(500L); stored.setId(500L); }
                    previous = stored; return insertRows;
                case "advancePlanDueDate":
                    assertEquals(100L,a[1]); assertEquals(stored.getScheduledDate(),a[2]); assertEquals(stored.getNextDueDate(),a[3]);
                    advances++; if (advanceRows == 1) plan.setNextDueDate((LocalDate)a[3]); return advanceRows;
                case "selectExecutionDetail":
                    assertEquals(500L,a[1]); if (missingDetail) return null;
                    var view = new MaintenanceExecutionVO(); BeanUtils.copyProperties(stored,view); return view;
                default: throw new AssertionError(m.getName());
            }
        });
        var factory = new DefaultListableBeanFactory(); factory.registerSingleton("currentAccessService",access); factory.registerSingleton("maintenancePlanMapper",mapper);
        var processor = new AutowiredAnnotationBeanPostProcessor(); processor.setBeanFactory(factory);
        var service = new MaintenancePlanServerImpl(); processor.processInjection(service); return service;
    }
    MaintenanceExecutionVO execute() { return service().createExecution(100L,key,request); }
    void rejects(int status) {
        assertEquals(status, assertThrows(ResponseStatusException.class,this::execute).getStatusCode().value());
        assertEquals(0,inserts); assertEquals(0,advances);
    }
}
