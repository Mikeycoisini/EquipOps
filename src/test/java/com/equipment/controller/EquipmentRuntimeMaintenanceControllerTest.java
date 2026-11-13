package com.equipment.controller;

import com.common.response.BusinessExceptionHandler;
import com.equipment.service.impl.EquipmentRuntimeMaintenanceService;
import com.equipops.domain.vo.EquipmentRuntimeMaintenanceVO;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EquipmentRuntimeMaintenanceControllerTest {
    private static final String PATH = "/api/equipment/20/runtime-maintenance";
    private final EquipmentRuntimeMaintenanceService service = mock(EquipmentRuntimeMaintenanceService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new EquipmentRuntimeMaintenanceController(service))
            .setControllerAdvice(new BusinessExceptionHandler()).build();

    @Test
    void invalidThresholdsReturnBusinessEnvelopeWithoutChangingPlan() throws Exception {
        for (String body : new String[]{"{}", "{\"thresholdHours\":0,\"expectedVersion\":0}", "{\"thresholdHours\":1.001,\"expectedVersion\":0}"}) {
            mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(400));
        }
        verifyNoInteractions(service);
    }

    @Test
    void currentAndConfiguredThresholdPreserveLongWorkOrderId() throws Exception {
        var plan = new EquipmentRuntimeMaintenanceVO();
        plan.setThresholdHours(new BigDecimal("5.25"));
        plan.setWorkOrderId(9007199254740993L);
        when(service.configure(20L, new BigDecimal("5.25"), 0L)).thenReturn(plan);
        when(service.current(20L)).thenReturn(plan);
        mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"thresholdHours\":5.25,\"expectedVersion\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.thresholdHours").value(5.25))
                .andExpect(jsonPath("$.data.workOrderId").value("9007199254740993"));
        mvc.perform(get(PATH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workOrderId").value("9007199254740993"));
    }
}
