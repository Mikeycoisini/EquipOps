package com.equipment.service.impl;

import com.auth.service.CurrentAccessService;
import com.common.response.BusinessExceptionHandler;
import com.equipment.mapper.EquipmentSparePartMapper;
import com.equipops.domain.entity.SparePartEntity;
import com.equipops.domain.vo.EquipmentSparePartVO;
import com.inventory.controller.SparePartCompatibilityController;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实业务、独立MVC及MyBatis映射检查，不访问数据库或加载认证链。 */
class SparePartCompatibilityTest {
    private boolean missing,empty;
    private int reads,listReads;
    private HttpStatus denied;
    private EquipmentSparePartServiceImpl service() {
        var access=(CurrentAccessService)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{CurrentAccessService.class},(p,m,a)->{
            assertEquals("requireActiveTenantId",m.getName());
            if(denied!=null)throw new ResponseStatusException(denied);return 7L;
        });
        var mapper=(EquipmentSparePartMapper)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{EquipmentSparePartMapper.class},(p,m,a)->{
            assertArrayEquals(new Object[]{7L,30L},a);
            switch(m.getName()) {
                case "selectTenantSparePart":
                    reads++;if(missing)return null;
                    var part=new SparePartEntity();part.setId(30L);part.setTenantId(7L);part.setEnabled(false);return part;
                case "selectRelationsBySparePart":
                    assertEquals(1,reads);listReads++;
                    if(empty)return List.of();
                    var item=new EquipmentSparePartVO();item.setId(500L);item.setTenantId(7L);item.setEquipmentId(20L);
                    item.setEquipmentName("空压机");item.setSparePartId(30L);item.setSparePartEnabled(false);return List.of(item);
                default:throw new AssertionError("查询不能加锁或写库: "+m.getName());
            }
        });
        return new EquipmentSparePartServiceImpl(access,mapper);
    }
    @Test void 普通成员可查停用备件的已有设备关系() {
        var result=service().listRelationsBySparePart(30L);
        assertEquals(1,result.size());assertEquals(20L,result.get(0).getEquipmentId());
        assertEquals("空压机",result.get(0).getEquipmentName());assertFalse(result.get(0).getSparePartEnabled());assertEquals(1,listReads);
    }
    @Test void 无适配关系返回空列表() {empty=true;assertTrue(service().listRelationsBySparePart(30L).isEmpty());assertEquals(1,listReads);}
    @Test void 非法ID不查询数据() {
        var service=service();for(Long id:Arrays.asList(null,0L,-1L))
            assertEquals(400,assertThrows(ResponseStatusException.class,()->service.listRelationsBySparePart(id)).getStatusCode().value());
        assertEquals(0,reads);assertEquals(0,listReads);
    }
    @Test void 不存在或其他企业备件返回404而非空列表() {
        missing=true;assertEquals(404,assertThrows(ResponseStatusException.class,()->service().listRelationsBySparePart(30L)).getStatusCode().value());assertEquals(0,listReads);
    }
    @Test void 无效账号企业或成员资格不能查数据() {
        for(var status:List.of(HttpStatus.UNAUTHORIZED,HttpStatus.FORBIDDEN,HttpStatus.CONFLICT)) {
            denied=status;assertEquals(status,assertThrows(ResponseStatusException.class,()->service().listRelationsBySparePart(30L)).getStatusCode());
        }
        assertEquals(0,reads);assertEquals(0,listReads);
    }
    @Test void GET返回设备关系且前端企业参数不生效() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new SparePartCompatibilityController(service())).setControllerAdvice(new BusinessExceptionHandler()).build();
        mvc.perform(get("/api/spare-parts/30/compatible-equipment").param("tenantId","999"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].equipmentId").value(20))
            .andExpect(jsonPath("$.data[0].sparePartEnabled").value(false));
    }
    @Test void GET非法ID与其他企业备件返回统一错误() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new SparePartCompatibilityController(service())).setControllerAdvice(new BusinessExceptionHandler()).build();
        mvc.perform(get("/api/spare-parts/0/compatible-equipment")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        missing=true;
        mvc.perform(get("/api/spare-parts/30/compatible-equipment")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(404));
    }
    @Test void SQL普通查询备件并按企业备件隔离关联且保留停用停机资料() throws Exception {
        var config=new Configuration();String resource="mapper/EquipmentSparePartMapper.xml";
        try(var stream=getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream);new XMLMapperBuilder(stream,config,resource,config.getSqlFragments()).parse();
        }
        var args=Map.of("tenantId",7L,"sparePartId",30L);
        String partSql=config.getMappedStatement("com.equipment.mapper.EquipmentSparePartMapper.selectTenantSparePart").getBoundSql(args).getSql().replaceAll("\\s+"," ").trim();
        assertTrue(partSql.contains("WHERE tenant_id = ? AND id = ?"));assertFalse(partSql.contains("FOR UPDATE"));assertFalse(partSql.contains("enabled ="));
        var method=EquipmentSparePartMapper.class.getMethod("selectRelationsBySparePart",Long.class,Long.class);
        var parameters=new ParamNameResolver(config,method).getNamedParams(new Object[]{7L,30L});
        var statement=config.getMappedStatement("com.equipment.mapper.EquipmentSparePartMapper.selectRelationsBySparePart");
        var bound=statement.getBoundSql(parameters);String sql=bound.getSql().replaceAll("\\s+"," ").trim();
        assertTrue(sql.contains("e.tenant_id = r.tenant_id AND e.id = r.equipment_id"));
        assertTrue(sql.contains("p.tenant_id = r.tenant_id AND p.id = r.spare_part_id"));
        assertTrue(sql.contains("WHERE r.tenant_id = ? AND r.spare_part_id = ?"));assertTrue(sql.endsWith("ORDER BY r.id DESC"));
        assertFalse(sql.contains("p.enabled ="));assertFalse(sql.contains("e.status"));assertFalse(sql.contains("FOR UPDATE"));
        assertEquals(List.of("tenantId","sparePartId"),bound.getParameterMappings().stream().map(p->p.getProperty()).toList());
        Map<Integer,Object> values=new HashMap<>();
        var jdbc=(PreparedStatement)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{if(m.getName().startsWith("set"))values.put((Integer)a[0],a[1]);return null;});
        new DefaultParameterHandler(statement,parameters,bound).setParameters(jdbc);assertEquals(Map.of(1,7L,2,30L),values);
    }
}
