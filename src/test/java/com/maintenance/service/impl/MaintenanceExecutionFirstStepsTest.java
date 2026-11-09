package com.maintenance.service.impl;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** 保留已完成前3步的权限和计划范围反例，成功分支现验证完整执行流程。 */
class MaintenanceExecutionFirstStepsTest {
    private final MaintenanceExecutionFixture f = new MaintenanceExecutionFixture();
    @Test void 本企业负责人可以完成到期计划() { assertNotNull(f.execute()); assertEquals(1,f.inserts); assertEquals(1,f.advances); }
    @Test void 成员无效时不查询计划() { f.denied=true; f.rejects(403); assertEquals(0,f.planReads); }
    @Test void 无效主键不查询计划() {
        var service=f.service();
        for(Long id:Arrays.asList(null,0L,-1L)) assertEquals(400,assertThrows(ResponseStatusException.class,()->service.createExecution(id,f.key,f.request)).getStatusCode().value());
        assertEquals(0,f.planReads);
    }
    @Test void 不存在或其他企业计划返回404() { f.missing=true; f.rejects(404); }
    @Test void 停用计划不能执行() { f.plan.setEnabled(false); f.rejects(409); }
    @Test void 非负责人不能执行() { f.plan.setAssigneeUserId(31L); f.rejects(403); }
    @Test void 管理员也不能代替其他负责人执行() { f.admin=true; f.plan.setAssigneeUserId(31L); f.rejects(403); }
}
