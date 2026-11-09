package com.maintenance.service.impl;

import com.auth.service.CurrentAccessService;
import com.equipops.domain.dto.maintenance.MaintenancePlanCreateDto;
import com.equipops.domain.entity.EquipmentEntity;
import com.equipops.domain.entity.MaintenancePlanEntity;
import com.equipops.domain.entity.SiteEntity;
import com.equipops.domain.enums.EquipmentStatus;
import com.equipops.domain.enums.MaintenancePlanType;
import com.equipops.domain.vo.MaintenancePlanVO;
import com.maintenance.mapper.MaintenancePlanMapper;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** 验证真实业务方法的校验、可信归属及写入结果；数据库调用由内存代理替代。 */
class MaintenancePlanServiceTest {
    private EquipmentEntity equipment = new EquipmentEntity();
    private SiteEntity site = new SiteEntity();
    private int assigneeCount = 1;
    private int insertRows = 1;
    private boolean denyAdmin;
    private boolean missingDetail;
    private boolean missingGeneratedId;
    private int writes;
    private MaintenancePlanEntity saved;

    private MaintenancePlanCreateDto request() {
        return new MaintenancePlanCreateDto("每周巡检", 20L, MaintenancePlanType.INSPECTION,
                "检查异响", 7, LocalDate.now(ZoneId.of("Asia/Shanghai")), 30L);
    }

    private MaintenancePlanServerImpl service() {
        equipment.setId(20L); equipment.setTenantId(7L); equipment.setSiteId(40L);
        equipment.setStatus(EquipmentStatus.STOPPED);
        site.setId(40L); site.setTenantId(7L); site.setEnabled(true);
        CurrentAccessService access = (CurrentAccessService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{CurrentAccessService.class}, (p, method, args) -> {
                    if (method.getName().equals("requireTenantAdminTenantId")) {
                        if (denyAdmin) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员");
                        return 7L;
                    }
                    if (method.getName().equals("requireEnabledUserId")) return 10L;
                    throw new AssertionError(method.getName());
                });
        MaintenancePlanMapper mapper = (MaintenancePlanMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{MaintenancePlanMapper.class}, (p, method, args) -> {
                    switch (method.getName()) {
                        case "selectTenantEquipment":
                            assertArrayEquals(new Object[]{7L, 20L}, args);
                            return equipment;
                        case "selectTenantSite":
                            assertArrayEquals(new Object[]{7L, 40L}, args);
                            return site;
                        case "countEnabledAssignee":
                            assertArrayEquals(new Object[]{7L, 30L}, args);
                            return assigneeCount;
                        case "insertPlan":
                            writes++;
                            saved = (MaintenancePlanEntity) args[0];
                            if (!missingGeneratedId) saved.setId(100L);
                            return insertRows;
                        case "selectPlanDetail":
                            assertArrayEquals(new Object[]{7L, 100L}, args);
                            if (missingDetail) return null;
                            MaintenancePlanVO result = new MaintenancePlanVO();
                            result.setId(100L); result.setTenantId(7L); result.setSiteId(40L);
                            result.setEquipmentAssetCode("EQ-20"); result.setEquipmentName("空压机");
                            result.setAssigneeName("维修员");
                            return result;
                        default: throw new AssertionError(method.getName());
                    }
                });
        var factory = new DefaultListableBeanFactory();
        factory.registerSingleton("currentAccessService", access);
        factory.registerSingleton("maintenancePlanMapper", mapper);
        var processor = new AutowiredAnnotationBeanPostProcessor();
        processor.setBeanFactory(factory);
        var service = new MaintenancePlanServerImpl();
        processor.processInjection(service);
        return service;
    }

    private void rejects(int status, MaintenancePlanServerImpl service, MaintenancePlanCreateDto request) {
        var error = assertThrows(ResponseStatusException.class, () -> service.createPlan(request));
        assertEquals(status, error.getStatusCode().value());
        assertEquals(0, writes);
    }

    @Test void 当天计划与停机设备允许创建且归属取自可信上下文() {
        var result = service().createPlan(request());
        assertNotNull(result);
        assertEquals(100L, result.getId());
        assertEquals("空压机", result.getEquipmentName());
        assertEquals("维修员", result.getAssigneeName());
        assertEquals(1, writes);
        assertEquals(7L, saved.getTenantId()); assertEquals(40L, saved.getSiteId());
        assertEquals(10L, saved.getCreatedBy()); assertEquals(20L, saved.getEquipmentId());
        assertEquals(30L, saved.getAssigneeUserId()); assertTrue(saved.getEnabled());
        assertEquals(MaintenancePlanType.INSPECTION, saved.getPlanType());
        assertEquals("每周巡检", saved.getName()); assertEquals("检查异响", saved.getContent());
        assertEquals(7, saved.getCycleDays()); assertEquals(request().getNextDueDate(), saved.getNextDueDate());
        assertNull(saved.getCreatedAt()); assertNull(saved.getUpdatedAt());
    }

    @Test void 普通成员不能创建() { denyAdmin = true; rejects(403, service(), request()); }
    @Test void 昨天的到期日拒绝写入() {
        var request = request(); request.setNextDueDate(request.getNextDueDate().minusDays(1));
        rejects(400, service(), request);
    }
    @Test void 未知或其他企业设备拒绝写入() {
        var service = service(); equipment = null; rejects(404, service, request());
    }
    @Test void 未知或其他企业厂区拒绝写入() {
        var service = service(); site = null; rejects(409, service, request());
    }
    @Test void 停用厂区拒绝写入() {
        var service = service(); site.setEnabled(false); rejects(409, service, request());
    }
    @Test void 非本企业或成员账号停用的负责人拒绝写入() {
        assigneeCount = 0; rejects(400, service(), request());
    }
    @Test void 插入未成功时不能返回成功资料() {
        insertRows = 0;
        var error = assertThrows(ResponseStatusException.class, () -> service().createPlan(request()));
        assertEquals(500, error.getStatusCode().value());
    }
    @Test void 未回填主键时不能回查或返回成功() {
        missingGeneratedId = true;
        var error = assertThrows(ResponseStatusException.class, () -> service().createPlan(request()));
        assertEquals(500, error.getStatusCode().value());
    }
    @Test void 回查失败时抛异常由事务回滚() {
        missingDetail = true;
        var error = assertThrows(ResponseStatusException.class, () -> service().createPlan(request()));
        assertEquals(500, error.getStatusCode().value());
        // 此处验证失败会抛出异常；不将内存代理测试当作真实数据库回滚验证。
        assertEquals(1, writes);
    }
}
