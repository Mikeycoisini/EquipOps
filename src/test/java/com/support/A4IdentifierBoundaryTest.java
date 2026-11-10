package com.support;
import com.equipops.domain.vo.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
class A4IdentifierBoundaryTest {
 @Test void nestedHistoryIdentifiersRemainExactStrings(){
  long id=9007199254740993L;var log=new WorkOrderLogVO();log.setId(id);log.setWorkOrderId(id);log.setActorUserId(id);
  var part=new WorkOrderPartVO();part.setId(id);part.setWorkOrderId(id);part.setSparePartId(id);
  var mapper=new ObjectMapper();
  for(var value:new Object[]{log,part}){var tree=mapper.valueToTree(value);assertTrue(tree.get("id").isString());assertEquals(Long.toString(id),tree.get("workOrderId").asText());}
 }
}
