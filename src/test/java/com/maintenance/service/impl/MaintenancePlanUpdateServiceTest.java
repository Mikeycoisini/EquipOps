package com.maintenance.service.impl;

import com.auth.service.CurrentAccessService;
import com.equipops.domain.dto.maintenance.MaintenancePlanUpdateDto;
import com.equipops.domain.entity.EquipmentEntity;
import com.equipops.domain.entity.SiteEntity;
import com.equipops.domain.enums.MaintenancePlanType;
import com.equipops.domain.vo.MaintenancePlanVO;
import com.maintenance.mapper.MaintenancePlanMapper;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** 修改逻辑的日期边界和可编辑字段测试；不代表真实数据库事务验证。 */
class MaintenancePlanUpdateServiceTest {
    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
    private final MaintenancePlanVO original = new MaintenancePlanVO();
    private final MaintenancePlanUpdateDto request = new MaintenancePlanUpdateDto();
    private boolean denied, missing, equipmentMissing, siteDisabled, detailMissing, staleSnapshot;
    private int assignees = 1, rows = 1, reads, writes;
    MaintenancePlanUpdateServiceTest() {
        original.setId(100L); original.setTenantId(7L); original.setSiteId(40L); original.setEquipmentId(20L);
        original.setName("旧计划"); original.setPlanType(MaintenancePlanType.INSPECTION); original.setContent("检查异响");
        original.setCycleDays(7); original.setNextDueDate(today.minusDays(3)); original.setAssigneeUserId(30L);
        original.setEnabled(false); original.setCreatedBy(10L); original.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        request.setName("旧计划"); request.setEquipmentId(20L); request.setPlanType(MaintenancePlanType.INSPECTION);
        request.setContent("检查异响"); request.setCycleDays(7); request.setNextDueDate(today.minusDays(3)); request.setAssigneeUserId(30L);
    }
    private MaintenancePlanServerImpl service() {
        var access = (CurrentAccessService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{CurrentAccessService.class}, (p,m,a) -> {
            assertEquals("requireTenantAdminTenantId", m.getName());
            if (denied) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员"); return 7L;
        });
        var mapper = (MaintenancePlanMapper) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{MaintenancePlanMapper.class}, (p,m,a) -> {
            assertEquals(7L, a[0]);
            switch (m.getName()) {
                case "selectPlanDetailForUpdate":
                    assertEquals(100L, a[1]); reads++;
                    if (missing || (reads > 1 && detailMissing)) return null;
                    original.setEquipmentName("设备名称"); original.setAssigneeName("负责人"); return original;
                case "selectTenantEquipment":
                case "selectTenantEquipmentForUpdate":
                    assertEquals(request.getEquipmentId(), a[1]);
                    if (equipmentMissing) return null;
                    var equipment = new EquipmentEntity(); equipment.setSiteId(request.getEquipmentId() == 21L ? 41L : 40L); return equipment;
                case "selectTenantSite":
                case "selectTenantSiteForUpdate":
                    assertEquals(request.getEquipmentId() == 21L ? 41L : 40L, a[1]);
                    var site = new SiteEntity(); site.setEnabled(!siteDisabled || (staleSnapshot && m.getName().equals("selectTenantSite"))); return site;
                case "countEnabledAssignee": assertEquals(request.getAssigneeUserId(), a[1]); return staleSnapshot ? 1 : assignees;
                case "selectEnabledAssigneeForUpdate": assertEquals(request.getAssigneeUserId(), a[1]); return assignees == 1 ? request.getAssigneeUserId() : null;
                case "updatePlan":
                    assertEquals(100L, a[1]); assertEquals(1, reads); writes++;
                    assertEquals(request.getEquipmentId() == 21L ? 41L : 40L, a[3]);
                    if (rows == 1) { BeanUtils.copyProperties(a[2], original); original.setSiteId((Long)a[3]); }
                    return rows;
                default: throw new AssertionError(m.getName());
            }
        });
        var factory = new DefaultListableBeanFactory(); factory.registerSingleton("currentAccessService", access); factory.registerSingleton("maintenancePlanMapper", mapper);
        var processor = new AutowiredAnnotationBeanPostProcessor(); processor.setBeanFactory(factory);
        var service = new MaintenancePlanServerImpl(); processor.processInjection(service); return service;
    }
    private void rejects(int code) {
        assertEquals(code, assertThrows(ResponseStatusException.class, () -> service().updatePlan(100L, request)).getStatusCode().value());
        assertEquals(0, writes);
    }
    @Test void 更换设备继承新厂区且保留停用状态创建人和创建时间() {
        request.setName("新计划"); request.setEquipmentId(21L); request.setAssigneeUserId(31L);
        var result = service().updatePlan(100L, request);
        assertEquals("新计划", result.getName()); assertEquals(21L, result.getEquipmentId()); assertEquals(41L, result.getSiteId());
        assertEquals(31L, result.getAssigneeUserId()); assertEquals(7L, result.getTenantId());
        assertFalse(result.getEnabled()); assertEquals(10L, result.getCreatedBy());
        assertEquals(LocalDateTime.of(2026,1,1,0,0), result.getCreatedAt());
        assertEquals("设备名称", result.getEquipmentName()); assertEquals(2, reads); assertEquals(1, writes);
    }
    @Test void 所有字段未变化仍成功且使用当前读回查() {
        assertNotNull(service().updatePlan(100L, request)); assertEquals(0, writes); assertEquals(2, reads);
    }
    @Test void 修改周期允许保留原逾期日期且不自动推算日期() {
        request.setCycleDays(30); var result = service().updatePlan(100L, request);
        assertEquals(30, result.getCycleDays()); assertEquals(today.minusDays(3), result.getNextDueDate()); assertEquals(1, writes);
    }
    @Test void 改成另一个过去日期会拒绝() { request.setNextDueDate(today.minusDays(1)); rejects(400); }
    @Test void 改成今天允许() { request.setNextDueDate(today); assertEquals(today, service().updatePlan(100L, request).getNextDueDate()); }
    @Test void 改成未来日期允许() { request.setNextDueDate(today.plusDays(7)); assertEquals(today.plusDays(7), service().updatePlan(100L, request).getNextDueDate()); }
    @Test void 缺少日期拒绝() { request.setNextDueDate(null); rejects(400); }
    @Test void 非管理员不读取原计划() { denied = true; rejects(403); assertEquals(0, reads); }
    @Test void 不存在或其他企业计划拒绝() { missing = true; rejects(404); }
    @Test void 无效主键不查询数据库() {
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> service().updatePlan(0L, request)).getStatusCode().value()); assertEquals(0, reads);
    }
    @Test void 设备不属于本企业拒绝() { equipmentMissing = true; rejects(404); }
    @Test void 厂区停用拒绝() { siteDisabled = true; rejects(409); }
    @Test void 负责人非本企业或停用拒绝() { assignees = 0; rejects(400); }
    @Test void 写入失败抛异常() { request.setName("新计划"); rows = 0; assertEquals(500, assertThrows(ResponseStatusException.class, () -> service().updatePlan(100L, request)).getStatusCode().value()); }
    @Test void 回查失败抛异常交给事务回滚() { request.setName("新计划"); detailMissing = true; assertEquals(500, assertThrows(ResponseStatusException.class, () -> service().updatePlan(100L, request)).getStatusCode().value()); }
    @Test void 等待后厂区已停用不能被旧快照放行() { staleSnapshot = true; siteDisabled = true; rejects(409); }
    @Test void 等待后负责人已停用不能被旧快照放行() { staleSnapshot = true; assignees = 0; rejects(400); }

}
