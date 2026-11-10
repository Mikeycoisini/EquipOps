package com.tenant.controller;

import com.equipops.domain.vo.SiteScopeVO;
import com.tenant.service.SiteScopeService;
import com.tenant.service.SiteService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP契约：字符串ID、非负版本校验、空数组完整撤销、业务状态及内部脱敏。 */
class SiteScopeControllerTest {
    @Test void 管理员读取范围保持字符串ID及数值版本() throws Exception {
        var service=mock(SiteScopeService.class);
        when(service.getScopes(100L)).thenReturn(new SiteScopeVO("100",2L,List.of("9007199254740993")));
        var mvc=MockMvcBuilders.standaloneSetup(new SiteScopeController(service)).setControllerAdvice(new SiteScopeExceptionHandler()).build();
        mvc.perform(get("/api/tenant-members/100/site-scopes")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.membershipId").value("100"))
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.siteIds[0]").value("9007199254740993"));
    }
    @Test void PUT空集合完整撤销且非法版本请求不进入业务() throws Exception {
        var service=mock(SiteScopeService.class);
        when(service.updateScopes(eq(100L),any())).thenReturn(new SiteScopeVO("100",3L,List.of()));
        var mvc=MockMvcBuilders.standaloneSetup(new SiteScopeController(service)).setControllerAdvice(new SiteScopeExceptionHandler()).build();
        mvc.perform(put("/api/tenant-members/100/site-scopes").contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":2,\"siteIds\":[]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(3));
        clearInvocations(service);
        mvc.perform(put("/api/tenant-members/100/site-scopes").contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":-1,\"siteIds\":[]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(service);
    }
    @Test void 小数版本或小数厂区ID不得截断成有效整数() throws Exception {
        var service=mock(SiteScopeService.class);
        when(service.updateScopes(eq(100L),any())).thenReturn(new SiteScopeVO("100",1L,List.of("11")));
        var mvc=MockMvcBuilders.standaloneSetup(new SiteScopeController(service)).setControllerAdvice(new SiteScopeExceptionHandler()).build();
        for(String body:List.of("{\"expectedVersion\":0.9,\"siteIds\":[11]}",
                "{\"expectedVersion\":0,\"siteIds\":[11.9]}",
                "{\"expectedVersion\":0,\"siteIds\":[\"11.9\"]}")) {
            mvc.perform(put("/api/tenant-members/100/site-scopes").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        }
        verifyNoInteractions(service);
    }
    @Test void 十进制字符串厂区ID与整数版本兼容现有范围契约() throws Exception {
        var service=mock(SiteScopeService.class);
        when(service.updateScopes(eq(100L),any())).thenReturn(new SiteScopeVO("100",1L,List.of("9007199254740993")));
        var mvc=MockMvcBuilders.standaloneSetup(new SiteScopeController(service)).setControllerAdvice(new SiteScopeExceptionHandler()).build();
        mvc.perform(put("/api/tenant-members/100/site-scopes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0,\"siteIds\":[\"9007199254740993\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.siteIds[0]").value("9007199254740993"));
        var argument=org.mockito.ArgumentCaptor.forClass(com.equipops.domain.dto.tenant.SiteScopeUpdateDto.class);
        verify(service).updateScopes(eq(100L),argument.capture());
        assertEquals(List.of(9_007_199_254_740_993L),argument.getValue().getSiteIds());
    }
    @Test void 保留权限与冲突状态且不泄露内部正文() throws Exception {
        var service=mock(SiteScopeService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new SiteScopeController(service)).setControllerAdvice(new SiteScopeExceptionHandler()).build();
        for(var status:List.of(HttpStatus.FORBIDDEN,HttpStatus.NOT_FOUND,HttpStatus.CONFLICT)) {
            doThrow(new ResponseStatusException(status,"业务原因")).when(service).getScopes(100L);
            mvc.perform(get("/api/tenant-members/100/site-scopes")).andExpect(status().is(status.value())).andExpect(jsonPath("$.message").value("业务原因"));
        }
        doThrow(new IllegalStateException("private-sql-marker")).when(service).getScopes(100L);
        String body=mvc.perform(get("/api/tenant-members/100/site-scopes")).andExpect(status().isInternalServerError()).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("private-sql-marker"));
    }
    @Test void 厂区目录使用专用catalog并保留默认GET() throws Exception {
        var service=mock(SiteService.class);
        when(service.getSiteCatalog()).thenReturn(List.of());when(service.getDefaultSites()).thenReturn(List.of());
        var mvc=MockMvcBuilders.standaloneSetup(new SiteController(service)).setControllerAdvice(new SiteScopeExceptionHandler()).build();
        mvc.perform(get("/api/sites/catalog")).andExpect(status().isOk());verify(service).getSiteCatalog();
        mvc.perform(get("/api/sites")).andExpect(status().isOk());verify(service).getDefaultSites();
    }
}
