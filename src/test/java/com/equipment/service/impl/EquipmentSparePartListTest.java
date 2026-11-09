package com.equipment.service.impl;

import com.auth.service.CurrentAccessService;
import com.common.response.BusinessExceptionHandler;
import com.equipment.controller.EquipmentSparePartController;
import com.equipment.mapper.EquipmentSparePartMapper;
import com.equipops.domain.entity.EquipmentEntity;
import com.equipops.domain.enums.EquipmentStatus;
import com.equipops.domain.vo.EquipmentSparePartVO;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实查询业务与MVC契约；只替代身份、数据库边界，不加载数据库或安全链。 */
class EquipmentSparePartListTest {
    private boolean missing, empty;
    private HttpStatus denied;
    private int resourceReads, listReads;

    private EquipmentSparePartServiceImpl service() {
        var access = (CurrentAccessService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{CurrentAccessService.class}, (p, m, a) -> {
                    // 若错误要求管理员或额外读取不可信企业，这里会失败。
                    assertEquals("requireActiveTenantId", m.getName());
                    if (denied != null) throw new ResponseStatusException(denied, "无效身份或企业成员关系");
                    return 7L;
                });
        var mapper = (EquipmentSparePartMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{EquipmentSparePartMapper.class}, (p, m, a) -> {
                    assertArrayEquals(new Object[]{7L, 20L}, a);
                    switch (m.getName()) {
                        case "selectTenantEquipment":
                            resourceReads++;
                            if (missing) return null;
                            var equipment = new EquipmentEntity();
                            equipment.setId(20L); equipment.setTenantId(7L); equipment.setStatus(EquipmentStatus.STOPPED);
                            return equipment;
                        case "selectRelationsByEquipment":
                            assertEquals(1, resourceReads); listReads++;
                            if (empty) return List.of();
                            var disabled = new EquipmentSparePartVO();
                            disabled.setId(501L); disabled.setTenantId(7L); disabled.setEquipmentId(20L);
                            disabled.setSparePartId(31L); disabled.setSparePartName("旧滤芯"); disabled.setSparePartEnabled(false);
                            var enabled = new EquipmentSparePartVO();
                            enabled.setId(500L); enabled.setTenantId(7L); enabled.setEquipmentId(20L);
                            enabled.setSparePartId(30L); enabled.setSparePartName("新滤芯"); enabled.setSparePartEnabled(true);
                            return List.of(disabled, enabled);
                        default: throw new AssertionError("查询不能加行锁或写库: " + m.getName());
                    }
                });
        return new EquipmentSparePartServiceImpl(access, mapper);
    }

    @Test void 普通成员可查询停机设备且保留停用备件及其状态() {
        var result = service().listRelations(20L);
        assertEquals(2, result.size());
        assertEquals(501L, result.get(0).getId());
        assertEquals("旧滤芯", result.get(0).getSparePartName());
        assertFalse(result.get(0).getSparePartEnabled());
        assertTrue(result.get(1).getSparePartEnabled());
        assertEquals(1, listReads);
    }

    @Test void 本企业设备未关联备件返回空列表() {
        empty = true;
        assertTrue(service().listRelations(20L).isEmpty());
        assertEquals(1, resourceReads); assertEquals(1, listReads);
    }

    @Test void 无效设备ID返回400且不查数据库() {
        var service = service();
        for (Long id : Arrays.asList(null, 0L, -1L)) {
            assertEquals(400, assertThrows(ResponseStatusException.class, () -> service.listRelations(id)).getStatusCode().value());
        }
        assertEquals(0, resourceReads); assertEquals(0, listReads);
    }

    @Test void 不存在或其他企业设备返回404而非空列表() {
        missing = true;
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service().listRelations(20L)).getStatusCode().value());
        assertEquals(1, resourceReads); assertEquals(0, listReads);
    }

    @Test void 未登录失效成员或未选择企业在查询前被拒绝() {
        for (HttpStatus status : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.CONFLICT)) {
            denied = status;
            assertEquals(status.value(), assertThrows(ResponseStatusException.class, () -> service().listRelations(20L)).getStatusCode().value());
        }
        assertEquals(0, resourceReads); assertEquals(0, listReads);
    }

    @Test void GET路径返回统一列表及停用状态() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new EquipmentSparePartController(service()))
                .setControllerAdvice(new BusinessExceptionHandler()).build();
        mvc.perform(get("/api/equipment/20/compatible-spare-parts"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].sparePartName").value("旧滤芯"))
                .andExpect(jsonPath("$.data[0].sparePartEnabled").value(false));
    }

    @Test void GET设备无关联返回空数组() throws Exception {
        empty = true;
        var mvc = MockMvcBuilders.standaloneSetup(new EquipmentSparePartController(service()))
                .setControllerAdvice(new BusinessExceptionHandler()).build();
        mvc.perform(get("/api/equipment/20/compatible-spare-parts"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test void GET不存在设备返回统一404响应() throws Exception {
        missing = true;
        var mvc = MockMvcBuilders.standaloneSetup(new EquipmentSparePartController(service()))
                .setControllerAdvice(new BusinessExceptionHandler()).build();
        mvc.perform(get("/api/equipment/20/compatible-spare-parts"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
    }
}
