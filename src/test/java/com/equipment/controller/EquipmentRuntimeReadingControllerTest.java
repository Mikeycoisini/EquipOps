package com.equipment.controller;

import com.common.response.BusinessExceptionHandler;
import com.equipment.service.impl.EquipmentRuntimeReadingService;
import com.equipops.domain.vo.EquipmentRuntimeReadingVO;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EquipmentRuntimeReadingControllerTest {
    private final EquipmentRuntimeReadingService service = mock(EquipmentRuntimeReadingService.class);
    private final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new EquipmentRuntimeReadingController(service))
            .setControllerAdvice(new BusinessExceptionHandler()).build();

    @Test
    void invalidHoursDoNotReachTheService() throws Exception {
        for (String body : new String[]{"{}", "{\"hours\":-1}", "{\"hours\":1.001}", "{\"hours\":1000000000000}"}) {
            mvc.perform(post("/api/equipment/20/runtime-readings").contentType(MediaType.APPLICATION_JSON)
                    .content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test
    void firstReadingCanBeZero() throws Exception {
        var reading = new EquipmentRuntimeReadingVO();
        reading.setId(9L);
        reading.setHours(new BigDecimal("0.00"));
        reading.setRecordedBy(3L);
        reading.setRecordedAt(1760000000000L);
        when(service.record(20L, BigDecimal.ZERO)).thenReturn(reading);

        mvc.perform(post("/api/equipment/20/runtime-readings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"hours\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("9"))
                .andExpect(jsonPath("$.data.hours").value(0.0))
                .andExpect(jsonPath("$.data.recordedBy").value("3"))
                .andExpect(jsonPath("$.data.recordedAt").value(1760000000000L));
    }

    @Test
    void decreasingHoursReturnConflictWithReason() throws Exception {
        when(service.record(20L, BigDecimal.ONE)).thenThrow(
                new ResponseStatusException(HttpStatus.CONFLICT, "累计运行小时不能小于最新读数"));
        mvc.perform(post("/api/equipment/20/runtime-readings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"hours\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("累计运行小时不能小于最新读数"));
    }

    @Test
    void noReadingsReturnsEmptyArray() throws Exception {
        when(service.recent(20L)).thenReturn(List.of());
        mvc.perform(get("/api/equipment/20/runtime-readings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }
}
