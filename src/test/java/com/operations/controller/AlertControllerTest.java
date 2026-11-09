package com.operations.controller;
import com.config.Exception.AlertExceptionHandler;
import com.operations.service.AlertQueryService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.equipops.domain.vo.AlertEventVO;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class AlertControllerTest {
 @Test void queryValidationAndPublicResponse()throws Exception {
  var service=mock(AlertQueryService.class);
  var vo=new AlertEventVO();vo.setId(8L);
  var p=new Page<AlertEventVO>(1,10);p.setTotal(1);p.setRecords(java.util.List.of(vo));
  when(service.page(any())).thenReturn(p);
  var mvc=MockMvcBuilders.standaloneSetup(new AlertController(service)).setControllerAdvice(new AlertExceptionHandler()).build();
  mvc.perform(get("/api/operations/alerts")).andExpect(status().isOk()).andExpect(jsonPath("$.data.records[0].id").value(8))
    .andExpect(jsonPath("$.data.records[0].fingerprint").doesNotExist());
  mvc.perform(get("/api/operations/alerts").param("status","BAD")).andExpect(status().isBadRequest());
  mvc.perform(get("/api/operations/alerts").param("size","101")).andExpect(status().isBadRequest());
 }
 @Test void mapperXmlLoadsAndScopesEveryQuery()throws Exception {
  var c=new org.apache.ibatis.session.Configuration();
  try(var input=getClass().getResourceAsStream("/mapper/AlertEventMapper.xml")) {
   new org.apache.ibatis.builder.xml.XMLMapperBuilder(input,c,"mapper/AlertEventMapper.xml",c.getSqlFragments()).parse();
  }
  var q=new com.equipops.domain.dto.operations.AlertPageQueryDto();
  var params=new java.util.HashMap<String,Object>();params.put("tenantId",11L);params.put("query",q);params.put("id",8L);
  for(var name:java.util.List.of("selectEventPage","selectEventDetail")) {
   var sql=c.getMappedStatement("com.operations.mapper.AlertEventMapper."+name).getBoundSql(params).getSql();
   org.junit.jupiter.api.Assertions.assertTrue(sql.contains("e.tenant_id=?"));
   org.junit.jupiter.api.Assertions.assertTrue(sql.contains("r.tenant_id=?"));
  }
 }
}
