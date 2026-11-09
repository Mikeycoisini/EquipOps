package com.equipment.mapper;

import com.equipops.domain.entity.EquipmentSparePartEntity;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 实际MyBatis查询和参数绑定，不连接数据库。 */
class EquipmentSparePartSqlTest {
    private Configuration config() throws Exception {
        var config=new Configuration();String resource="mapper/EquipmentSparePartMapper.xml";
        try(var input=getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);new XMLMapperBuilder(input,config,resource,config.getSqlFragments()).parse();
        }
        return config;
    }
    @Test void 资源查询分别限定企业并使用当前读锁() throws Exception {
        var config=config();var params=Map.of("tenantId",7L,"equipmentId",20L,"sparePartId",30L);
        for(String name:List.of("selectTenantEquipmentForUpdate","selectTenantSparePartForUpdate")) {
            String sql=config.getMappedStatement("com.equipment.mapper.EquipmentSparePartMapper."+name).getBoundSql(params).getSql().replaceAll("\\s+"," ").trim();
            assertTrue(sql.contains("WHERE tenant_id = ? AND id = ?"));assertTrue(sql.endsWith("FOR UPDATE"));
        }
    }
    @Test void 插入仅写关联字段并回填主键不调整库存() throws Exception {
        var config=config();var statement=config.getMappedStatement("com.equipment.mapper.EquipmentSparePartMapper.insertRelation");
        var value=new EquipmentSparePartEntity();value.setTenantId(7L);value.setEquipmentId(20L);value.setSparePartId(30L);value.setCreatedBy(10L);
        var method=EquipmentSparePartMapper.class.getMethod("insertRelation",EquipmentSparePartEntity.class);
        Object params=new ParamNameResolver(config,method).getNamedParams(new Object[]{value});var bound=statement.getBoundSql(params);
        assertEquals("INSERT INTO equipment_spare_parts (tenant_id, equipment_id, spare_part_id, created_by) VALUES (?, ?, ?, ?)",bound.getSql().replaceAll("\\s+"," ").trim());
        assertArrayEquals(new String[]{"relation.id"},statement.getKeyProperties());
        Map<Integer,Object> values=new HashMap<>();PreparedStatement jdbc=(PreparedStatement)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{
            if(m.getName().startsWith("set"))values.put((Integer)a[0],a[1]);return null;
        });
        new DefaultParameterHandler(statement,params,bound).setParameters(jdbc);assertEquals(Map.of(1,7L,2,20L,3,30L,4,10L),values);
    }
    @Test void 查重和回查带企业条件且不会隐藏停用备件() throws Exception {
        var config=config();
        var duplicate=config.getMappedStatement("com.equipment.mapper.EquipmentSparePartMapper.findRelationId").getBoundSql(Map.of("tenantId",7L,"equipmentId",20L,"sparePartId",30L));
        assertEquals(List.of("tenantId","equipmentId","sparePartId"),duplicate.getParameterMappings().stream().map(p->p.getProperty()).toList());
        String sql=config.getMappedStatement("com.equipment.mapper.EquipmentSparePartMapper.selectRelationDetail").getBoundSql(Map.of("tenantId",7L,"id",500L)).getSql().replaceAll("\\s+"," ").trim();
        assertTrue(sql.contains("e.tenant_id = r.tenant_id AND e.id = r.equipment_id"));
        assertTrue(sql.contains("p.tenant_id = r.tenant_id AND p.id = r.spare_part_id"));
        assertTrue(sql.contains("WHERE r.tenant_id = ? AND r.id = ?"));assertFalse(sql.contains("p.enabled ="));
    }
    @Test void 列表查询按企业设备隔离且保留停用备件并倒序排列() throws Exception {
        var config = config();
        String prefix = "com.equipment.mapper.EquipmentSparePartMapper.";
        assertTrue(config.hasStatement(prefix + "selectTenantEquipment"), "需要普通设备归属查询");
        assertTrue(config.hasStatement(prefix + "selectRelationsByEquipment"), "需要设备适配列表查询");
        var parameters = Map.of("tenantId", 7L, "equipmentId", 20L);
        var device = config.getMappedStatement(prefix + "selectTenantEquipment").getBoundSql(parameters);
        String deviceSql = device.getSql().replaceAll("\\s+", " ").trim();
        assertTrue(deviceSql.contains("WHERE tenant_id = ? AND id = ?"));
        assertFalse(deviceSql.contains("FOR UPDATE"));
        var method = EquipmentSparePartMapper.class.getMethod("selectRelationsByEquipment", Long.class, Long.class);
        Object args = new ParamNameResolver(config, method).getNamedParams(new Object[]{7L, 20L});
        var statement = config.getMappedStatement(prefix + "selectRelationsByEquipment");
        var bound = statement.getBoundSql(args);
        String sql = bound.getSql().replaceAll("\\s+", " ").trim();
        assertTrue(sql.contains("e.tenant_id = r.tenant_id AND e.id = r.equipment_id"));
        assertTrue(sql.contains("p.tenant_id = r.tenant_id AND p.id = r.spare_part_id"));
        assertTrue(sql.contains("WHERE r.tenant_id = ? AND r.equipment_id = ?"));
        assertTrue(sql.endsWith("ORDER BY r.id DESC"));
        assertFalse(sql.contains("p.enabled ="));
        assertFalse(sql.contains("FOR UPDATE"));
        assertEquals(List.of("tenantId", "equipmentId"), bound.getParameterMappings().stream().map(p -> p.getProperty()).toList());
        Map<Integer, Object> values = new HashMap<>();
        PreparedStatement jdbc = (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class}, (p, m, a) -> {
            if (m.getName().startsWith("set")) values.put((Integer) a[0], a[1]);
            return null;
        });
        new DefaultParameterHandler(statement, args, bound).setParameters(jdbc);
        assertEquals(Map.of(1, 7L, 2, 20L), values);
    }

}
