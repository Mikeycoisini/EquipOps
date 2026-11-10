package com.support;

import com.audit.mapper.AuditEventMapper;
import com.audit.service.impl.AuditEventServiceImpl;
import com.auth.service.CurrentAccessService;
import com.equipops.domain.dto.audit.AuditAppendCommand;
import com.equipops.domain.entity.AuditEventEntity;
import com.equipops.domain.enums.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 厂区配置动作的审计白名单拒绝名称地址正文，仅保存固定字段与布尔状态。 */
class SiteManagementAuditTest {
    @Test void 创建事件仅保留启用与默认布尔值() throws Exception {
        var mapper=mock(AuditEventMapper.class);when(mapper.insert(any(AuditEventEntity.class))).thenReturn(1);
        var service=new AuditEventServiceImpl(mapper,mock(CurrentAccessService.class),new ObjectMapper());
        service.appendSuccess(new AuditAppendCommand(7L,19L,AuditAction.SITE_CREATED,AuditObjectType.SITE,100L,
                Map.of("enabled",true,"isDefault",false,"name","private-name","address","private-address","siteCode","private-code"),null,null));
        var capture=ArgumentCaptor.forClass(AuditEventEntity.class);verify(mapper).insert(capture.capture());
        assertNotNull(capture.getValue().getChangesJson());
        var tree=new ObjectMapper().readTree(capture.getValue().getChangesJson());
        assertTrue(tree.get("enabled").asBoolean());assertFalse(tree.get("isDefault").asBoolean());
        assertFalse(capture.getValue().getChangesJson().contains("private-"));
    }
    @Test void 配置修改只允许固定变化字段与真实布尔前后值() throws Exception {
        var mapper=mock(AuditEventMapper.class);when(mapper.insert(any(AuditEventEntity.class))).thenReturn(1);
        var service=new AuditEventServiceImpl(mapper,mock(CurrentAccessService.class),new ObjectMapper());
        service.appendSuccess(new AuditAppendCommand(7L,19L,AuditAction.SITE_UPDATED,AuditObjectType.SITE,100L,
                Map.of("changedFields",List.of("name","address"),"beforeEnabled",true,"afterEnabled",true,"name","private-name"),null,null));
        var capture=ArgumentCaptor.forClass(AuditEventEntity.class);verify(mapper).insert(capture.capture());
        assertNotNull(capture.getValue().getChangesJson());
        var tree=new ObjectMapper().readTree(capture.getValue().getChangesJson());
        assertEquals("name",tree.get("changedFields").get(0).asText());
        assertFalse(capture.getValue().getChangesJson().contains("private-"));
    }
}
