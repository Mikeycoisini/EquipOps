package com.team;
import com.audit.service.AuditEventService;
import com.auth.model.TenantAccess;
import com.auth.service.CurrentAccessService;
import com.equipops.domain.entity.TenantEntity;
import com.equipops.domain.enums.*;
import com.team.dto.TeamCreateDto;
import com.team.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
/** 团队失败审计使用可信身份、固定错误码及空目标，不能泄露请求正文。 */
class TeamAuditFacadeTest {
    @Test void 普通成员拒绝有独立审计并保留原始403(){
        var delegate=mock(TeamService.class);var audit=mock(AuditEventService.class);var access=mock(CurrentAccessService.class);
        var tenant=new TenantEntity();tenant.setId(7L);when(access.requireTenantAccess()).thenReturn(new TenantAccess(19L,tenant,21L,Set.of()));
        var request=new TeamCreateDto(UUID.randomUUID().toString(),"T","private-name",true);var failure=new ResponseStatusException(HttpStatus.FORBIDDEN,"private-error");
        when(delegate.create(request)).thenThrow(failure);
        assertSame(failure,assertThrows(ResponseStatusException.class,()->new AuditedTeamService(delegate,audit,access).create(request)));
        verify(audit).appendDenied(argThat(event->event.getTenantId()==7L && event.getActorUserId()==19L && event.getAction()==AuditAction.TEAM_CREATED
                && event.getObjectType()==AuditObjectType.TEAM && event.getObjectId()==null && event.getChanges().isEmpty() && event.getErrorCode().equals("FORBIDDEN")));
    }
}
