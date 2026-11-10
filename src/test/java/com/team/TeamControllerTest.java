package com.team;
import com.team.controller.*;
import com.team.dto.TeamMembersDto;
import com.team.service.TeamService;
import com.team.vo.TeamVO;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
/** 团队HTTP契约、严格整数边界与字符串BIGINT兼容性。 */
class TeamControllerTest {
    private TeamVO result(){return new TeamVO("9007199254740993","7","T","团队",true,1,List.of("9007199254740993"),List.of("11"));}
    @Test void POST成功200且ID字符串完整保精度() throws Exception {
        var service=mock(TeamService.class);when(service.create(any())).thenReturn(result());
        var mvc=MockMvcBuilders.standaloneSetup(new TeamController(service)).setControllerAdvice(new TeamExceptionHandler()).build();
        mvc.perform(post("/api/teams").contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\""+UUID.randomUUID()+"\",\"teamCode\":\"T\",\"name\":\"团队\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("9007199254740993")).andExpect(jsonPath("$.data.version").value(1));
    }
    @Test void 成员字符串ID可用但小数或负版本拒绝() throws Exception {
        var service=mock(TeamService.class);when(service.members(eq(100L),any())).thenReturn(result());
        var mvc=MockMvcBuilders.standaloneSetup(new TeamController(service)).setControllerAdvice(new TeamExceptionHandler()).build();
        String request=UUID.randomUUID().toString();
        mvc.perform(put("/api/teams/100/members").contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\""+request+"\",\"expectedVersion\":0,\"membershipIds\":[\"9007199254740993\"]}"))
                .andExpect(status().isOk());
        var capture=ArgumentCaptor.forClass(TeamMembersDto.class);verify(service).members(eq(100L),capture.capture());assertEquals(List.of(9_007_199_254_740_993L),capture.getValue().getMembershipIds());
        clearInvocations(service);
        for(String extra:List.of("\"expectedVersion\":0.1,\"membershipIds\":[11]","\"expectedVersion\":0,\"membershipIds\":[11.1]","\"expectedVersion\":-1,\"membershipIds\":[]"))
            mvc.perform(put("/api/teams/100/members").contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\""+request+"\","+extra+"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(service);
    }
    @Test void 业务403与409保留且500不输出内部正文() throws Exception {
        var service=mock(TeamService.class);var mvc=MockMvcBuilders.standaloneSetup(new TeamController(service)).setControllerAdvice(new TeamExceptionHandler()).build();
        for(var code:List.of(HttpStatus.FORBIDDEN,HttpStatus.CONFLICT)) {
            doThrow(new ResponseStatusException(code,"业务原因")).when(service).detail(100L);
            mvc.perform(get("/api/teams/100")).andExpect(status().is(code.value())).andExpect(jsonPath("$.message").value("业务原因"));
        }
        doThrow(new IllegalStateException("private-sql-marker")).when(service).detail(100L);
        var response=mvc.perform(get("/api/teams/100")).andExpect(status().isInternalServerError()).andReturn().getResponse().getContentAsString();assertFalse(response.contains("private-sql-marker"));
    }
}
