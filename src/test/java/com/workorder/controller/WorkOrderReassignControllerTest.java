package com.workorder.controller;

import com.equipops.domain.dto.workorder.WorkOrderReassignDto;
import com.equipops.domain.vo.WorkOrderVO;
import com.workorder.service.WorkOrderReassignService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 改派HTTP边界：严格数字、精确字符串ID、规范理由与真实错误状态。 */
class WorkOrderReassignControllerTest {
 private static final String PATH="/api/work-orders/9007199254740993/reassign";
 private static final long ID=9_007_199_254_740_993L;
 private MockMvc mvc(WorkOrderReassignService service){return MockMvcBuilders.standaloneSetup(new WorkOrderReassignController(service)).setControllerAdvice(new WorkOrderReassignExceptionHandler()).build();}
 private String body(String version,String user,String team,String reason){return "{\"requestId\":\"12345678-1234-1234-1234-123456789abc\",\"expectedVersion\":"+version+",\"targetAssigneeUserId\":"+user+",\"targetTeamId\":"+team+",\"reason\":\""+reason+"\"}";}
 private String valid(){return body("0","\"9007199254740993\"","\"9007199254740995\"","交接");}
 private WorkOrderVO result(){var result=new WorkOrderVO();result.setId(ID);result.setAssigneeUserId(ID);result.setTeamId(ID+2);result.setVersion(1L);return result;}
 @Test void JSON理由绑定必须与setter使用相同规范正文(){
  var direct=new WorkOrderReassignDto();direct.setReason(" x ");assertEquals("x",direct.getReason());
  var decoded=new tools.jackson.databind.ObjectMapper().readValue(body("0","11","null"," x "),WorkOrderReassignDto.class);
  assertEquals("x",decoded.getReason(),"JSON构造绑定也必须先trim而不能绕过setter规范化");
 }
 @Test void POST成功且路径和目标字符串BIGINT精确传入() throws Exception {
  var service=mock(WorkOrderReassignService.class);when(service.reassign(eq(ID),any())).thenReturn(result());
  mvc(service).perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(valid())).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("9007199254740993")).andExpect(jsonPath("$.data.teamId").value("9007199254740995")).andExpect(jsonPath("$.data.version").value(1));
  var capture=ArgumentCaptor.forClass(WorkOrderReassignDto.class);verify(service).reassign(eq(ID),capture.capture());assertEquals(ID,capture.getValue().getTargetAssigneeUserId());assertEquals(ID+2,capture.getValue().getTargetTeamId());
 }
 @Test void 权限不存在冲突保持真实状态且内部错误脱敏() throws Exception {
  var service=mock(WorkOrderReassignService.class);var mvc=mvc(service);
  for(var code:List.of(HttpStatus.FORBIDDEN,HttpStatus.NOT_FOUND,HttpStatus.CONFLICT)){
   doThrow(new ResponseStatusException(code,"业务原因")).when(service).reassign(eq(ID),any());
   mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(valid())).andExpect(status().is(code.value())).andExpect(jsonPath("$.message").value("业务原因"));
  }
  for(RuntimeException failure:List.of(new IllegalStateException("private-sql-marker"),new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"private-sql-marker"))){
   doThrow(failure).when(service).reassign(eq(ID),any());
   var response=mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(valid())).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value(500)).andReturn().getResponse().getContentAsString();assertFalse(response.contains("private-sql-marker"));
  }
 }
 @Test void 小数版本或目标ID以及字符串版本均拒绝且不调用业务() throws Exception {
  var service=mock(WorkOrderReassignService.class);var mvc=mvc(service);
  for(String request:List.of(body("0.1","11","null","交接"),body("0","11.1","null","交接"),body("0","11","12.1","交接"),body("\"0\"","11","null","交接"),body("-1","11","null","交接"),body("9007199254740992","11","null","交接"),body("0","\"11.1\"","null","交接"),body("0","11","\"12.1\"","交接")))
   mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
  verifyNoInteractions(service);
 }
 @Test void 理由按去首尾空白后长度校验且传入规范正文() throws Exception {
  var service=mock(WorkOrderReassignService.class);when(service.reassign(eq(ID),any())).thenReturn(result());var mvc=mvc(service);
  for(String reason:List.of(" x "," "+"中".repeat(1000)+" ")){
   mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("0","11","null",reason))).andExpect(status().isOk());
  }
  var capture=ArgumentCaptor.forClass(WorkOrderReassignDto.class);verify(service,times(2)).reassign(eq(ID),capture.capture());assertEquals("x",capture.getAllValues().get(0).getReason());assertEquals("中".repeat(1000),capture.getAllValues().get(1).getReason());
  clearInvocations(service);
  for(String reason:List.of("","   ","中".repeat(1001)," "+"中".repeat(1001)+" "))
   mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("0","11","null",reason))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
  verifyNoInteractions(service);
 }
}
