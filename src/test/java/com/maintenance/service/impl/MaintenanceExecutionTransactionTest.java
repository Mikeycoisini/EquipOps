package com.maintenance.service.impl;

import com.maintenance.service.MaintenancePlanServer;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** 验证本方法确实经过事务代理及其提交/回滚配置；资源为内存替身，不代表MySQL验证。 */
class MaintenanceExecutionTransactionTest {
    private static class MemoryTransactions extends AbstractPlatformTransactionManager {
        final MaintenanceExecutionFixture f;
        int begins, commits, rollbacks;
        java.time.LocalDate originalDue;
        MemoryTransactions(MaintenanceExecutionFixture f) { this.f=f; }
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction,TransactionDefinition definition) {
            assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED,definition.getIsolationLevel());
            originalDue=f.plan.getNextDueDate(); begins++;
        }
        @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++; f.stored=null; f.previous=null; f.plan.setNextDueDate(originalDue);
        }
    }
    private MaintenancePlanServer proxy(MaintenanceExecutionFixture f,MemoryTransactions transactions) {
        var proxy=new ProxyFactory(f.service());
        proxy.addAdvice(new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource()));
        return (MaintenancePlanServer)proxy.getProxy();
    }
    @Test void 完成登记经过读已提交事务并一起提交() {
        var f=new MaintenanceExecutionFixture(); var transactions=new MemoryTransactions(f);
        var result=proxy(f,transactions).createExecution(100L,f.key,f.request);
        assertEquals(1,transactions.begins); assertEquals(1,transactions.commits); assertEquals(0,transactions.rollbacks);
        assertEquals(result.getNextDueDate(),f.plan.getNextDueDate()); assertNotNull(f.stored);
    }
    @Test void 日期更新失败通过事务代理撤销执行记录() {
        var f=new MaintenanceExecutionFixture(); f.advanceRows=0; var original=f.plan.getNextDueDate();
        var transactions=new MemoryTransactions(f); var service=proxy(f,transactions);
        assertThrows(ResponseStatusException.class,()->service.createExecution(100L,f.key,f.request));
        assertEquals(1,f.inserts); assertEquals(1,transactions.rollbacks); assertEquals(0,transactions.commits);
        assertNull(f.stored); assertNull(f.previous); assertEquals(original,f.plan.getNextDueDate());
    }
    @Test void 回查失败通过事务代理同时撤销记录和日期() {
        var f=new MaintenanceExecutionFixture(); f.missingDetail=true; var original=f.plan.getNextDueDate();
        var transactions=new MemoryTransactions(f); var service=proxy(f,transactions);
        assertThrows(ResponseStatusException.class,()->service.createExecution(100L,f.key,f.request));
        assertEquals(1,f.advances); assertEquals(1,transactions.rollbacks); assertEquals(0,transactions.commits);
        assertNull(f.stored); assertEquals(original,f.plan.getNextDueDate());
    }
}
