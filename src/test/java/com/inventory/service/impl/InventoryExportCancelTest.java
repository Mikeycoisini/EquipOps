package com.inventory.service.impl;

import com.auth.model.TenantAccess;
import com.auth.service.CurrentAccessService;
import com.equipops.domain.entity.TenantEntity;
import com.equipops.domain.enums.InventoryExportStatus;
import com.equipops.domain.vo.InventoryExportTaskVO;
import com.inventory.mapper.InventoryExportTaskMapper;
import java.lang.reflect.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class InventoryExportCancelTest {
    InventoryExportDownloadServiceImpl service;
    InventoryExportServiceImpl exportService;
    InventoryExportTaskVO task;
    boolean admin=true, allowed=true, member=true, committed, commitFails, permissionLost, databaseFails;
    int writes, reads;
    List<String> calls=new ArrayList<>();
    ThreadPoolExecutor pool;
    FutureTask<Void> future;
    @BeforeEach void setup(){
        service=new InventoryExportDownloadServiceImpl();exportService=new InventoryExportServiceImpl();
        ReflectionTestUtils.setField(service,"exportServiceImpl",exportService);
        task=new InventoryExportTaskVO();task.setId(9L);task.setStatus(InventoryExportStatus.QUEUED);
        var tenant=new TenantEntity();tenant.setId(7L);
        ReflectionTestUtils.setField(service,"currentAccessService",proxy(CurrentAccessService.class,(p,m,a)->{
            assertEquals("requireTenantAccess",m.getName());
            if(!allowed)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"成员失效");
            return new TenantAccess(4L,tenant,3L,admin?Set.of("TENANT_ADMIN"):Set.of());
        }));
        ReflectionTestUtils.setField(service,"transactionTemplate",new TransactionTemplate(){
            @Override public <T>T execute(TransactionCallback<T> action){
                T value=action.doInTransaction(new SimpleTransactionStatus());
                if(commitFails)throw new IllegalStateException("commit failed");
                committed=true;return value;
            }
        });
        ReflectionTestUtils.setField(service,"taskMapper",proxy(InventoryExportTaskMapper.class,(p,m,a)->{
            calls.add(m.getName());
            switch(m.getName()){
                case "selectEnabledMembershipForUpdate":assertArrayEquals(new Object[]{7L,4L},a);return member?3L:null;
                case "selectOwnTaskForUpdate":
                case "selectOwnTask":reads++;assertArrayEquals(new Object[]{7L,4L,9L},a);return task;
                case "requestCancelOwnTask":
                    assertArrayEquals(new Object[]{7L,4L,9L},a);writes++;
                    if(databaseFails)throw new org.springframework.jdbc.BadSqlGrammarException("private","SELECT private_secret",new java.sql.SQLException("private"));
                    if(permissionLost)return 0;
                    task.setStatus(task.getStatus()==InventoryExportStatus.QUEUED?InventoryExportStatus.CANCELLED:InventoryExportStatus.CANCEL_REQUESTED);
                    if(task.getStatus()==InventoryExportStatus.CANCELLED)task.setFinishedAt(LocalDateTime.now());return 1;
                default:throw new AssertionError(m.getName());
            }
        }));
        pool=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(2));
        future=new FutureTask<>(()->null);pool.getQueue().add(future);
        @SuppressWarnings("unchecked") var executions=(Map<Long,FutureTask<Void>>)ReflectionTestUtils.getField(exportService,"executions");executions.put(9L,future);
        ReflectionTestUtils.setField(exportService,"exportThreadPool",pool);
    }
    @AfterEach void close(){pool.shutdownNow();}
    <T>T proxy(Class<T> type,InvocationHandler h){return type.cast(Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{type},h));}
    InventoryExportTaskVO cancel(Long id){return ReflectionTestUtils.invokeMethod(service,"cancel",id);}
    int status(Long id){return assertThrows(ResponseStatusException.class,()->cancel(id)).getStatusCode().value();}
    @Test void 管理员取消本人排队任务并在提交后释放本地队列(){
        assertEquals(InventoryExportStatus.CANCELLED,cancel(9L).getStatus());assertNotNull(task.getFinishedAt());assertTrue(committed);
        assertTrue(future.isCancelled());assertTrue(pool.getQueue().isEmpty());assertEquals(1,writes);
        assertEquals(List.of("selectEnabledMembershipForUpdate","selectOwnTaskForUpdate","requestCancelOwnTask","selectOwnTask"),calls);
    }
    @Test void 运行任务只记录取消请求由后台收尾(){task.setStatus(InventoryExportStatus.RUNNING);assertEquals(InventoryExportStatus.CANCEL_REQUESTED,cancel(9L).getStatus());assertNull(task.getFinishedAt());assertFalse(future.isCancelled());}
    @Test void 重复取消中请求不重写状态(){task.setStatus(InventoryExportStatus.CANCEL_REQUESTED);assertEquals(InventoryExportStatus.CANCEL_REQUESTED,cancel(9L).getStatus());assertEquals(0,writes);}
    @Test void 所有终态拒绝取消不覆盖结果(){for(var state:List.of(InventoryExportStatus.SUCCEEDED,InventoryExportStatus.FAILED,InventoryExportStatus.CANCELLED,InventoryExportStatus.REJECTED,InventoryExportStatus.EXPIRED)){task.setStatus(state);assertEquals(409,status(9L));assertEquals(state,task.getStatus());}assertEquals(0,writes);}
    @Test void 其他创建人其他企业或不存在统一404(){task=null;assertEquals(404,status(9L));assertEquals(0,writes);}
    @Test void 普通成员和失效成员不能取消(){admin=false;assertEquals(403,status(9L));admin=true;allowed=false;assertEquals(403,status(9L));assertEquals(0,reads);assertEquals(0,writes);}
    @Test void 参数非法不访问任务表(){for(Long id:Arrays.asList(null,0L,-1L))assertEquals(400,status(id));assertTrue(calls.isEmpty());}
    @Test void 加锁时成员失效返回403(){member=false;assertEquals(403,status(9L));assertEquals(0,reads);}
    @Test void 提交失败不能提前取消本地Future(){commitFails=true;assertThrows(RuntimeException.class,()->cancel(9L));assertFalse(future.isCancelled());assertEquals(1,pool.getQueue().size());}
    @Test void 写入时权限失效不谎报成功(){permissionLost=true;assertEquals(403,status(9L));assertFalse(future.isCancelled());}
    @Test void 新取消入口数据库异常不暴露SQL(){databaseFails=true;var failure=assertThrows(ResponseStatusException.class,()->cancel(9L));assertEquals(500,failure.getStatusCode().value());assertFalse(failure.getReason().contains("private"));assertFalse(future.isCancelled());}
    @Test void 外层事务中不能提前释放队列(){TransactionSynchronizationManager.setActualTransactionActive(true);try{assertEquals(500,status(9L));assertTrue(calls.isEmpty());assertFalse(future.isCancelled());}finally{TransactionSynchronizationManager.setActualTransactionActive(false);}}
}
