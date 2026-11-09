package com.support;
import com.equipops.domain.vo.AuditEventVO;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
class AuditIdentifierSerializationTest {
 @Test void bigintIdsAreStringsToPreserveBrowserPrecision() throws Exception {
  var event=new AuditEventVO();event.setId(Long.MAX_VALUE);event.setActorUserId(Long.MAX_VALUE);event.setObjectId(Long.MAX_VALUE);
  String json=new ObjectMapper().writeValueAsString(event);
  assertTrue(json.contains("\"id\":\"9223372036854775807\""));
  assertTrue(json.contains("\"actorUserId\":\"9223372036854775807\""));
  assertTrue(json.contains("\"objectId\":\"9223372036854775807\""));
 }
}
