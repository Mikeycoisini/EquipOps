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

/** 厂区范围审计只保留合法ID集合及安全版本，未知正文不得泄露。 */
class SiteScopeAuditTest {
    @Test void 范围变更保留完整前后集合与版本并丢弃自由文本() throws Exception {
        var mapper=mock(AuditEventMapper.class);
        when(mapper.insert(any(AuditEventEntity.class))).thenReturn(1);
        var json=new ObjectMapper();
        var service=new AuditEventServiceImpl(mapper,mock(CurrentAccessService.class),json);
        service.appendSuccess(new AuditAppendCommand(7L,19L,AuditAction.MEMBER_SITE_SCOPES_CHANGED,AuditObjectType.MEMBER,100L,
                Map.of("field","siteIds","before",List.of("11"),"after",List.of("9007199254740993"),"version",2L,"note","private-marker"),null,null));
        var capture=ArgumentCaptor.forClass(AuditEventEntity.class);
        verify(mapper).insert(capture.capture());
        var output=capture.getValue().getChangesJson();
        assertNotNull(output);
        var tree=json.readTree(output);
        assertEquals("11",tree.get("before").get(0).asText());
        assertEquals("9007199254740993",tree.get("after").get(0).asText());
        assertEquals(2,tree.get("version").asInt());
        assertFalse(output.contains("private-marker"));
    }
    @Test void 非法范围摘要整体拒绝而不回显输入() {
        var mapper=mock(AuditEventMapper.class);
        when(mapper.insert(any(AuditEventEntity.class))).thenReturn(1);
        var service=new AuditEventServiceImpl(mapper,mock(CurrentAccessService.class),new ObjectMapper());
        service.appendSuccess(new AuditAppendCommand(7L,19L,AuditAction.MEMBER_SITE_SCOPES_CHANGED,AuditObjectType.MEMBER,100L,
                Map.of("field","siteIds","before",List.of("private-marker"),"after",List.of("11"),"version",2L),null,null));
        var capture=ArgumentCaptor.forClass(AuditEventEntity.class);
        verify(mapper).insert(capture.capture());
        assertNull(capture.getValue().getChangesJson());
    }
}
