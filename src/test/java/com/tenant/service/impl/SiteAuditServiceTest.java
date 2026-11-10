package com.tenant.service.impl;

import com.audit.service.AuditEventService;
import com.auth.service.CurrentAccessService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.equipops.domain.dto.audit.AuditAppendCommand;
import com.equipops.domain.dto.tenant.SiteUpsertDto;
import com.equipops.domain.entity.SiteEntity;
import com.equipops.domain.enums.*;
import com.tenant.mapper.SiteMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 厂区成功审计必须与业务一起失败；无变更不重复追加，名称地址正文不进入审计。 */
class SiteAuditServiceTest {
    @Mock CurrentAccessService access;
    @Mock SiteMapper mapper;
    @Mock AuditEventService audit;
    @InjectMocks SiteServiceImpl service;
    private AutoCloseable mocks;
    private SiteEntity row;
    @BeforeEach void fixture() {
        mocks=MockitoAnnotations.openMocks(this);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),""),SiteEntity.class);
        when(access.requireTenantAdminTenantId()).thenReturn(7L);
        when(access.requireActiveTenantId()).thenReturn(7L);
        when(access.requireEnabledUserId()).thenReturn(19L);
        row=SiteEntity.builder().id(100L).tenantId(7L).siteCode("S1").name("原名称").address("原地址").enabled(true).isDefault(false).build();
        when(mapper.selectOne(any())).thenAnswer(invocation->row);
        when(mapper.insert(any(SiteEntity.class))).thenAnswer(invocation->{row=invocation.getArgument(0);row.setId(100L);return 1;});
        when(mapper.update(isNull(),any())).thenReturn(1);
    }
    @AfterEach void close() throws Exception {mocks.close();}
    @Test void 创建追加可信厂区成功事件且只保存布尔配置() {
        service.createSite(new SiteUpsertDto("S1","private-name","private-address",true));
        var captured=ArgumentCaptor.forClass(AuditAppendCommand.class);verify(audit).appendSuccess(captured.capture());
        var event=captured.getValue();assertEquals(AuditAction.SITE_CREATED,event.getAction());assertEquals(AuditObjectType.SITE,event.getObjectType());
        assertEquals(7L,event.getTenantId());assertEquals(19L,event.getActorUserId());assertEquals(100L,event.getObjectId());
        assertEquals(java.util.Map.of("enabled",true,"isDefault",false),event.getChanges());
    }
    @Test void 修改只记录变化字段名与启停前后值() {
        service.updateSite(100L,new SiteUpsertDto("S1","private-name","private-address",true));
        var captured=ArgumentCaptor.forClass(AuditAppendCommand.class);verify(audit).appendSuccess(captured.capture());
        var event=captured.getValue();assertEquals(AuditAction.SITE_UPDATED,event.getAction());
        assertEquals(java.util.List.of("name","address"),event.getChanges().get("changedFields"));
        assertEquals(true,event.getChanges().get("beforeEnabled"));assertEquals(true,event.getChanges().get("afterEnabled"));
        assertFalse(event.getChanges().toString().contains("private-"));
    }
    @Test void 相同规范化配置不重复追加审计() {
        service.updateSite(100L,new SiteUpsertDto(" S1 "," 原名称 "," 原地址 ",true));
        verifyNoInteractions(audit);
    }
    @Test void 创建审计失败向外传播以回滚业务() {
        doThrow(new IllegalStateException("受控审计失败")).when(audit).appendSuccess(any());
        assertThrows(IllegalStateException.class,()->service.createSite(new SiteUpsertDto("S1","名称",null,true)));
    }
}
