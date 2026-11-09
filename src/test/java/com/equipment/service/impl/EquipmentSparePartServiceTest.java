package com.equipment.service.impl;

import com.auth.service.CurrentAccessService;
import com.equipment.mapper.EquipmentSparePartMapper;
import com.equipops.domain.dto.equipment.EquipmentSparePartCreateDto;
import com.equipops.domain.entity.EquipmentEntity;
import com.equipops.domain.entity.EquipmentSparePartEntity;
import com.equipops.domain.entity.SparePartEntity;
import com.equipops.domain.enums.EquipmentStatus;
import com.equipops.domain.vo.EquipmentSparePartVO;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** 实际适配业务的身份、归属、启用及重复检查；不连接数据库。 */
class EquipmentSparePartServiceTest {
    private boolean denied,equipmentMissing,partMissing,duplicate,duplicateOnInsert,missingId,detailMissing,lockConflict;
    private Boolean enabled=true;
    private int resourceReads,inserts,rows=1;
    private EquipmentSparePartEntity saved;
    private final EquipmentSparePartCreateDto request=new EquipmentSparePartCreateDto(30L);
    private EquipmentSparePartServiceImpl service() {
        var access=(CurrentAccessService)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{CurrentAccessService.class},(p,m,a)->{
            if(m.getName().equals("requireTenantAdminTenantId")) {
                if(denied)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"需要管理员");return 7L;
            }
            assertEquals("requireEnabledUserId",m.getName());return 10L;
        });
        var mapper=(EquipmentSparePartMapper)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{EquipmentSparePartMapper.class},(p,m,a)->{
            switch(m.getName()) {
                case "selectTenantEquipmentForUpdate":
                    assertArrayEquals(new Object[]{7L,20L},a);resourceReads++;
                    if(lockConflict)throw new CannotAcquireLockException("private SQL");
                    if(equipmentMissing)return null;
                    var equipment=new EquipmentEntity();equipment.setStatus(EquipmentStatus.STOPPED);return equipment;
                case "selectTenantSparePartForUpdate":
                    assertEquals(1,resourceReads);assertArrayEquals(new Object[]{7L,30L},a);resourceReads++;
                    if(partMissing)return null;
                    var part=new SparePartEntity();part.setEnabled(enabled);return part;
                case "findRelationId":assertArrayEquals(new Object[]{7L,20L,30L},a);return duplicate?99L:null;
                case "insertRelation":
                    assertEquals(2,resourceReads);inserts++;
                    if(duplicateOnInsert)throw new DuplicateKeyException("private SQL");
                    saved=new EquipmentSparePartEntity();BeanUtils.copyProperties(a[0],saved);
                    if(!missingId){((EquipmentSparePartEntity)a[0]).setId(500L);saved.setId(500L);}return rows;
                case "selectRelationDetail":
                    assertArrayEquals(new Object[]{7L,500L},a);if(detailMissing)return null;
                    var result=new EquipmentSparePartVO();BeanUtils.copyProperties(saved,result);
                    result.setEquipmentAssetCode("EQ-20");result.setEquipmentName("空压机");result.setSparePartCode("SP-30");
                    result.setSparePartName("滤芯");result.setSparePartEnabled(true);result.setCreatedAt(LocalDateTime.of(2026,10,30,12,0));return result;
                default:throw new AssertionError(m.getName());
            }
        });
        return new EquipmentSparePartServiceImpl(access,mapper);
    }
    private void rejects(int status) {
        assertEquals(status,assertThrows(ResponseStatusException.class,()->service().createRelation(20L,request)).getStatusCode().value());assertEquals(0,inserts);
    }
    @Test void 管理员可为停机设备关联启用备件并返回完整资料() {
        var result=service().createRelation(20L,request);
        assertEquals(500L,result.getId());assertEquals(7L,saved.getTenantId());assertEquals(20L,saved.getEquipmentId());
        assertEquals(30L,saved.getSparePartId());assertEquals(10L,saved.getCreatedBy());assertNull(saved.getCreatedAt());
        assertEquals("空压机",result.getEquipmentName());assertEquals("滤芯",result.getSparePartName());assertTrue(result.getSparePartEnabled());
        assertNotNull(result.getCreatedAt());assertEquals(1,inserts);
    }
    @Test void 普通成员禁止操作() {denied=true;rejects(403);assertEquals(0,resourceReads);}
    @Test void 无效设备或备件ID不查询资源() {
        var service=service();for(Long id:Arrays.asList(null,0L,-1L)) {
            assertEquals(400,assertThrows(ResponseStatusException.class,()->service.createRelation(id,request)).getStatusCode().value());
            assertEquals(400,assertThrows(ResponseStatusException.class,()->service.createRelation(20L,new EquipmentSparePartCreateDto(id))).getStatusCode().value());
        }
        assertEquals(0,resourceReads);assertEquals(0,inserts);
    }
    @Test void 不存在或其他企业设备返回404() {equipmentMissing=true;rejects(404);assertEquals(1,resourceReads);}
    @Test void 不存在或其他企业备件返回404() {partMissing=true;rejects(404);}
    @Test void 停用或未知启用状态备件拒绝新增关联() {enabled=false;rejects(409);enabled=null;resourceReads=0;rejects(409);}
    @Test void 已有关联返回409() {duplicate=true;rejects(409);}
    @Test void 数据库唯一键并发冲突返回409() {
        duplicateOnInsert=true;assertEquals(409,assertThrows(ResponseStatusException.class,()->service().createRelation(20L,request)).getStatusCode().value());assertEquals(1,inserts);
    }
    @Test void 插入没有成功返回500() {rows=0;assertEquals(500,assertThrows(ResponseStatusException.class,()->service().createRelation(20L,request)).getStatusCode().value());}
    @Test void 主键未回填时返回500() {missingId=true;assertEquals(500,assertThrows(ResponseStatusException.class,()->service().createRelation(20L,request)).getStatusCode().value());}
    @Test void 回查失败抛异常交由事务回滚() {detailMissing=true;assertEquals(500,assertThrows(ResponseStatusException.class,()->service().createRelation(20L,request)).getStatusCode().value());}
    @Test void 资源锁冲突返回可重试409() {lockConflict=true;rejects(409);}
}
