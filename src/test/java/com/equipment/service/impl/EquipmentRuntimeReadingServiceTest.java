package com.equipment.service.impl;

import com.auth.scope.SiteWriteAccessService;
import com.auth.service.CurrentAccessService;
import com.equipment.mapper.EquipmentRuntimeReadingMapper;
import com.equipment.service.EquipmentService;
import com.equipops.domain.vo.EquipmentRuntimeReadingVO;
import com.equipops.domain.vo.EquipmentVO;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EquipmentRuntimeReadingServiceTest {
    private final EquipmentService equipment = mock(EquipmentService.class);
    private final EquipmentRuntimeReadingMapper mapper = mock(EquipmentRuntimeReadingMapper.class);
    private final SiteWriteAccessService siteAccess = mock(SiteWriteAccessService.class);
    private final CurrentAccessService access = mock(CurrentAccessService.class);
    private final EquipmentRuntimeReadingService service =
            new EquipmentRuntimeReadingService(equipment, mapper, siteAccess, access);

    @BeforeEach
    void device() {
        var asset = new EquipmentVO();
        asset.setTenantId(2L);
        asset.setSiteId(3L);
        when(equipment.getEquipmentDetail(4L)).thenReturn(asset);
        when(access.requireEnabledUserId()).thenReturn(5L);
        when(mapper.lockEquipment(2L, 3L, 4L)).thenReturn(4L);
    }

    @Test
    void increasingValueUsesTrustedActorAndDeviceScope() {
        previous("1.00");
        when(mapper.insert(eq(2L), eq(3L), eq(4L), any())).thenAnswer(call -> {
            EquipmentRuntimeReadingVO reading = call.getArgument(3);
            reading.setId(6L);
            return 1;
        });
        var reading = service.record(4L, new BigDecimal("1.25"));
        assertEquals(new BigDecimal("1.25"), reading.getHours());
        assertEquals(5L, reading.getRecordedBy());
        assertEquals(6L, reading.getId());
    }

    @Test
    void equalValueReturnsPreviousReading() {
        var latest = previous("15.50");
        assertSame(latest, service.record(4L, new BigDecimal("15.5")));
        verify(mapper, never()).insert(any(), any(), any(), any());
    }

    @Test
    void decreasingValueDoesNotWrite() {
        previous("15.50");
        var error = assertThrows(ResponseStatusException.class,
                () -> service.record(4L, new BigDecimal("15.49")));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(mapper, never()).insert(any(), any(), any(), any());
    }

    @Test
    void deniedSiteCannotWrite() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(siteAccess).lockSite(3L);
        var error = assertThrows(ResponseStatusException.class, () -> service.record(4L, BigDecimal.ZERO));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verify(mapper, never()).lockEquipment(any(), any(), any());
    }

    private EquipmentRuntimeReadingVO previous(String hours) {
        var reading = new EquipmentRuntimeReadingVO();
        reading.setHours(new BigDecimal(hours));
        when(mapper.recent(2L, 3L, 4L, 1)).thenReturn(List.of(reading));
        return reading;
    }
}
