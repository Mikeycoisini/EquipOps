package com.support;
import com.audit.controller.AuditEventController;
import com.audit.controller.MemberAuditExceptionHandler;
import com.audit.service.AuditEventService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
class MemberAuditErrorResponseTest {
 @Test void databaseFailureDoesNotReturnDatabaseOrCredentialText() throws Exception {
  var service=mock(AuditEventService.class);
  when(service.detail(1L)).thenThrow(new DataAccessResourceFailureException("private-sql-password-marker"));
  var mvc=MockMvcBuilders.standaloneSetup(new AuditEventController(service)).setControllerAdvice(new MemberAuditExceptionHandler()).build();
  mvc.perform(get("/api/audit-events/1")).andExpect(status().isInternalServerError())
   .andExpect(jsonPath("$.code").value(500)).andExpect(content().string(not(containsString("private"))));
 }
}
