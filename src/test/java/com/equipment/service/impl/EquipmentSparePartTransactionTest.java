package com.equipment.service.impl;

import com.auth.service.CurrentAccessService;
import com.equipment.mapper.EquipmentSparePartMapper;
import com.equipment.service.EquipmentSparePartService;
import com.equipops.domain.dto.equipment.EquipmentSparePartCreateDto;
import com.equipops.domain.entity.EquipmentEntity;
import com.equipops.domain.entity.EquipmentSparePartEntity;
import com.equipops.domain.entity.SparePartEntity;
import com.equipops.domain.vo.EquipmentSparePartVO;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** 真实Spring事务代理与内存资源验证，不代表真实MySQL回滚验证。 */
class EquipmentSparePartTransactionTest {
    private boolean inserted,missingDetail;
    private static class Transactions extends AbstractPlatformTransactionManager {
        final EquipmentSparePartTransactionTest fixture;
        int commits,rollbacks;
        Transactions(EquipmentSparePartTransactionTest fixture){this.fixture=fixture;}
        @Override protected Object doGetTransaction(){return new Object();}
        @Override protected void doBegin(Object transaction,TransactionDefinition definition){assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED,definition.getIsolationLevel());}
        @Override protected void doCommit(DefaultTransactionStatus status){commits++;}
        @Override protected void doRollback(DefaultTransactionStatus status){rollbacks++;fixture.inserted=false;}
    }
    private EquipmentSparePartService service(Transactions transactions) {
        var access=(CurrentAccessService)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{CurrentAccessService.class},(p,m,a)->{
            return m.getName().equals("requireTenantAdminTenantId")?7L:10L;
        });
        var mapper=(EquipmentSparePartMapper)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{EquipmentSparePartMapper.class},(p,m,a)->{
            switch(m.getName()) {
                case "selectTenantEquipmentForUpdate":return new EquipmentEntity();
                case "selectTenantSparePartForUpdate":var part=new SparePartEntity();part.setEnabled(true);return part;
                case "findRelationId":return null;
                case "insertRelation":inserted=true;((EquipmentSparePartEntity)a[0]).setId(500L);return 1;
                case "selectRelationDetail":if(missingDetail)return null;var result=new EquipmentSparePartVO();result.setId(500L);return result;
                default:throw new AssertionError(m.getName());
            }
        });
        var proxy=new ProxyFactory(new EquipmentSparePartServiceImpl(access,mapper));
        proxy.addAdvice(new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource()));
        return (EquipmentSparePartService)proxy.getProxy();
    }
    @Test void 插入和回查正常时提交事务(){
        var transactions=new Transactions(this);
        assertEquals(500L,service(transactions).createRelation(20L,new EquipmentSparePartCreateDto(30L)).getId());
        assertTrue(inserted);assertEquals(1,transactions.commits);assertEquals(0,transactions.rollbacks);
    }
    @Test void 回查失败时事务代理撤销插入(){
        missingDetail=true;var transactions=new Transactions(this);var service=service(transactions);
        assertThrows(ResponseStatusException.class,()->service.createRelation(20L,new EquipmentSparePartCreateDto(30L)));
        assertFalse(inserted);assertEquals(0,transactions.commits);assertEquals(1,transactions.rollbacks);
    }
}
