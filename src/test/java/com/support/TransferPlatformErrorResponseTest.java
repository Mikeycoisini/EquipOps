package com.support;

import com.common.response.TransferPlatformExceptionHandler;
import com.inventory.transfer.TransferController;
import com.inventory.transfer.TransferService;
import com.inventory.transfer.WarehouseCatalogService;
import com.platform.PlatformTenantController;
import com.platform.PlatformTenantService;
import com.tenant.controller.WarehouseController;
import com.tenant.service.WarehouseService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TransferPlatformErrorResponseTest {
    private final TransferService transfers = mock(TransferService.class);
    private final WarehouseCatalogService warehouses = mock(WarehouseCatalogService.class);
    private final PlatformTenantService platform = mock(PlatformTenantService.class);

    @Test
    void preservesBusinessReasonAndHttpStatus() throws Exception {
        when(transfers.detail(1)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "调拨单不存在"));
        mvc().perform(get("/api/transfers/1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("调拨单不存在"));
    }

    @Test
    void rejectsMalformedBodyWithoutEchoingIt() throws Exception {
        mvc().perform(post("/api/platform/tenants").contentType("application/json").content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请求参数格式有误"));
    }

    @Test
    void hidesDatabaseFailureDetails() throws Exception {
        when(warehouses.catalog()).thenThrow(new DataAccessResourceFailureException("internal connection data"));
        mvc().perform(get("/api/warehouses/catalog"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("操作暂时无法完成，请稍后重试"));
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new TransferController(transfers),
                        new WarehouseController(mock(WarehouseService.class), warehouses),
                        new PlatformTenantController(platform))
                .setControllerAdvice(new TransferPlatformExceptionHandler()).build();
    }
}
