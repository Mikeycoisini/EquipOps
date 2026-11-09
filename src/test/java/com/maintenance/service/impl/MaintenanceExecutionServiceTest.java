package com.maintenance.service.impl;

import com.equipops.domain.entity.MaintenanceExecutionEntity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.web.server.ResponseStatusException;

class MaintenanceExecutionServiceTest {
    private final MaintenanceExecutionFixture f=new MaintenanceExecutionFixture();
    @Test void 按实际完成日期推进并保存完整历史快照() {
        var result=f.execute();
        assertEquals(500L,result.getId()); assertEquals(7L,result.getTenantId()); assertEquals(100L,result.getPlanId());
        assertEquals(20L,result.getEquipmentId()); assertEquals(40L,result.getSiteId()); assertEquals(30L,result.getExecutedBy());
        assertEquals("每周巡检",result.getPlanName()); assertEquals("检查异响",result.getPlanContent());
        assertEquals("空压机",result.getEquipmentName()); assertEquals("EQ-20",result.getEquipmentAssetCode());
        assertEquals("维修员",result.getExecutedByName()); assertEquals("已完成巡检",result.getResult());
        assertEquals(f.today.minusDays(3),result.getScheduledDate());
        assertEquals(result.getExecutedAt().toLocalDate().plusDays(7),result.getNextDueDate());
        assertEquals(result.getNextDueDate(),f.plan.getNextDueDate());
        f.plan.setName("新计划"); assertEquals("每周巡检",f.stored.getPlanName());
    }
    @Test void 相同请求重试返回原记录且不再次推进() {
        var first=f.execute(); var second=f.execute();
        assertEquals(first.getId(),second.getId()); assertEquals(first.getExecutedAt(),second.getExecutedAt());
        assertEquals(1,f.inserts); assertEquals(1,f.advances);
    }
    @Test void 原请求在停用及改负责人后仍可由原执行人取回() {
        var first=f.execute(); f.plan.setEnabled(false); f.plan.setAssigneeUserId(31L);
        assertEquals(first.getId(),f.execute().getId()); assertEquals(1,f.inserts);
    }
    @Test void 相同请求号改结果返回409() {
        f.execute(); f.request.setResult("不同结果");
        assertEquals(409,assertThrows(ResponseStatusException.class,f::execute).getStatusCode().value()); assertEquals(1,f.inserts);
    }
    @Test void 同一请求号属于其他计划或操作人时拒绝() {
        var previous=new MaintenanceExecutionEntity(); previous.setPlanId(999L); previous.setExecutedBy(30L); previous.setResult("已完成巡检");
        f.previous=previous; f.rejects(409);
        previous.setPlanId(100L); previous.setExecutedBy(31L); f.rejects(409);
    }
    @Test void 新请求不能提前完成下一个周期() {
        f.execute(); f.key="exec-2"; f.previous=null;
        assertEquals(409,assertThrows(ResponseStatusException.class,f::execute).getStatusCode().value()); assertEquals(1,f.inserts);
    }
    @Test void 尚未到期拒绝登记() { f.plan.setNextDueDate(f.today.plusDays(1)); f.rejects(409); }
    @Test void 当天到期允许登记() { f.plan.setNextDueDate(f.today); assertNotNull(f.execute()); }
    @Test void 本期已经登记但计划被重新安排到同一天时拒绝() { f.periods=1; f.rejects(409); }
    @Test void 空白或过长结果拒绝写入() {
        f.request.setResult("  "); f.rejects(400); f.request.setResult("a".repeat(2001)); f.rejects(400);
    }
    @Test void 无效请求号拒绝写入() { f.key="bad key"; f.rejects(400); f.key="a".repeat(65); f.rejects(400); }
    @Test void 周期异常拒绝推进() { f.plan.setCycleDays(0); f.rejects(409); }
    @Test void 插入失败或主键未回填时抛异常() {
        f.insertRows=0;
        assertEquals(500,assertThrows(ResponseStatusException.class,f::execute).getStatusCode().value()); assertEquals(0,f.advances);
    }
    @Test void 主键未回填不推进日期() {
        f.missingId=true; assertEquals(500,assertThrows(ResponseStatusException.class,f::execute).getStatusCode().value()); assertEquals(0,f.advances);
    }
    @Test void 日期更新失败抛异常由事务回滚() {
        f.advanceRows=0; assertEquals(409,assertThrows(ResponseStatusException.class,f::execute).getStatusCode().value());
        assertEquals(1,f.inserts); assertEquals(1,f.advances);
    }
    @Test void 回查失败抛异常由事务回滚() {
        f.missingDetail=true; assertEquals(500,assertThrows(ResponseStatusException.class,f::execute).getStatusCode().value());
    }
    @Test void 请求号并发冲突不能当成别人的操作成功() {
        f.duplicateWinner=new MaintenanceExecutionEntity(); f.duplicateWinner.setPlanId(999L); f.duplicateWinner.setExecutedBy(30L); f.duplicateWinner.setResult("已完成巡检");
        assertEquals(409,assertThrows(ResponseStatusException.class,f::execute).getStatusCode().value()); assertEquals(0,f.advances);
    }
    @Test void 锁冲突返回可重试409() { f.lockConflict=true; f.rejects(409); }
    @Test void 等待计划锁后成员被撤销不能继续写入() { f.revokeAfterLock=true; f.rejects(403); }
    @Test void 等待计划锁后成员被撤销不能重放原记录() {
        f.execute(); f.planReads=0; f.revokeAfterLock=true;
        assertEquals(403,assertThrows(ResponseStatusException.class,f::execute).getStatusCode().value());
        assertEquals(1,f.inserts); assertEquals(1,f.advances);
    }

}
