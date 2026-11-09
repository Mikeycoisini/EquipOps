package com.inventory.service.impl;
import com.auth.service.CurrentAccessService;
import com.equipops.domain.entity.*;
import com.equipops.domain.vo.*;
import com.equipops.domain.dto.inventory.WarehouseStockMinQuantityUpdateDto;
import com.inventory.mapper.WarehouseStockMapper;
import com.workorder.mapper.*;
import com.workorder.service.impl.WorkOrderServiceImpl;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
class S1ResponseCompletionTest {
 private <T>T proxy(Class<T> type,InvocationHandler handler){return type.cast(Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{type},handler));}
 private WarehouseStockServiceImpl threshold(int rows, boolean present){
  var access=proxy(CurrentAccessService.class,(p,m,a)->{assertEquals("requireTenantAdminTenantId",m.getName());return 7L;});
  var mapper=proxy(WarehouseStockMapper.class,(p,m,a)->{
   switch(m.getName()){
    case "selectOne": return new WarehouseStockEntity();
    case "updateMinStockQuantity": assertArrayEquals(new Object[]{7L,12L,new BigDecimal("10.000")},a);return rows;
    case "selectStockDetail":
     assertArrayEquals(new Object[]{7L,12L},a);if(!present)return null;
     var vo=new WarehouseStockVO();vo.setId(12L);vo.setWarehouseName("默认仓库");vo.setPartName("轴承");vo.setSiteId(3L);vo.setQuantity(new BigDecimal("5.000"));vo.setMinStockQuantity(new BigDecimal("10.000"));vo.setLowStock(true);return vo;
    default:throw new AssertionError(m.getName());
   }
  });
  return new WarehouseStockServiceImpl(access,mapper);
 }
 @Test void 阈值更新返回完整关联信息且同值零行更新也可成功(){
  for(int rows:new int[]{0,1}){
   var vo=threshold(rows,true).updateMinStockQuantity(12L,new WarehouseStockMinQuantityUpdateDto(new BigDecimal("10.000")));
   assertEquals("默认仓库",vo.getWarehouseName());assertEquals("轴承",vo.getPartName());assertEquals(3L,vo.getSiteId());assertTrue(vo.getLowStock());assertEquals(new BigDecimal("5.000"),vo.getQuantity());
  }
 }
 @Test void 阈值更新后无法读取资料不能返回成功(){
  assertEquals(404,assertThrows(ResponseStatusException.class,()->threshold(1,false).updateMinStockQuantity(12L,new WarehouseStockMinQuantityUpdateDto(new BigDecimal("10.000")))).getStatusCode().value());
 }
 @Test void 日志及工单详情均保留姓名与退回原因(){
  var access=proxy(CurrentAccessService.class,(p,m,a)->7L);
  var order=new WorkOrderEntity();order.setId(11L);order.setTenantId(7L);
  var orders=proxy(WorkOrderMapper.class,(p,m,a)->{
   switch(m.getName()){
    case "selectOne":return order;
    case "selectWorkOrderEquipment":return new EquipmentVO();
    case "selectWorkOrderParts":return List.of();
    default:throw new AssertionError(m.getName());
   }
  });
  var log=new WorkOrderLogVO();log.setActorName("验收员");log.setDetail("需要继续维修");log.setActorUserId(9L);
  var logs=proxy(WorkOrderLogMapper.class,(p,m,a)->{assertEquals("selectViewByWorkOrderId",m.getName());assertArrayEquals(new Object[]{7L,11L},a);return List.of(log);});
  var service=new WorkOrderServiceImpl(orders,logs,null,null,access,null);
  assertEquals("验收员",service.getWorkOrderLogs(11L).get(0).getActorName());
  var result=service.getWorkOrderDetail(11L).getTimeline().get(0);
  assertEquals("验收员",result.getActorName());assertEquals("需要继续维修",result.getDetail());assertEquals(9L,result.getActorUserId());
 }
}