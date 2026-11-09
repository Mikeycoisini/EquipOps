package com.inventory.service.impl;

import com.auth.model.TenantAccess;
import com.auth.service.CurrentAccessService;
import com.equipops.domain.dto.inventory.InventoryExportCreateDto;
import com.equipops.domain.entity.*;
import com.equipops.domain.enums.InventoryExportStatus;
import com.equipops.domain.vo.*;
import com.inventory.mapper.InventoryExportTaskMapper;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** 数据库和执行器使用替身；实际执行Service和文件写入，不连接业务库。 */
class InventoryExportServiceTest {
    @TempDir Path root;
    InventoryExportServiceImpl service;
    InventoryExportTaskEntity task;
    List<InventoryTransactionVO> rows = new ArrayList<>();
    InventoryExportStatus state = InventoryExportStatus.QUEUED;
    boolean committed, permitted=true, owned=true, claimed=true, published=true, live=true;
    boolean reject, rejectFails, publishThrows, revokeAfterBatch, cancelAfterBatch;
    boolean keepFiles=true;
    int renewals, reaped;
    Runnable beforeBatch=()->{}, maintenanceLookup=()->{};
    int batches, failCalls;
    String failureCode, storageKey;
    Runnable pending;

    @SuppressWarnings("unchecked")
    <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{type},handler);
    }
    @BeforeEach void setup() {
        service = new InventoryExportServiceImpl();
        task=InventoryExportTaskEntity.builder().id(9L).tenantId(7L).createdBy(1L)
                .ownerInstanceId((String)ReflectionTestUtils.getField(service,"instanceId"))
                .runToken(UUID.randomUUID().toString()).status(state).build();
        var tenant=new TenantEntity(); tenant.setId(7L);
        var access=new TenantAccess(1L,tenant,2L,Set.of());
        ReflectionTestUtils.setField(service,"currentAccessService",proxy(CurrentAccessService.class,(p,m,a)->access));
        ReflectionTestUtils.setField(service,"transactionTemplate",new TransactionTemplate() {
            @Override public <T> T execute(TransactionCallback<T> action) {
                T result=action.doInTransaction(new SimpleTransactionStatus()); committed=true; return result;
            }
        });
        ReflectionTestUtils.setField(service,"taskMapper",proxy(InventoryExportTaskMapper.class,(p,m,a)-> {
            switch(m.getName()) {
                case "selectEnabledMembershipForUpdate": return 2L;
                case "selectActiveTaskIdsForUpdate": return List.of();
                case "insertQueuedTask":
                    var created=(InventoryExportTaskEntity)a[0]; created.setId(9L);
                    task.setRunToken(created.getRunToken()); return 1;
                case "selectOwnTask":
                    var vo=new InventoryExportTaskVO(); vo.setId(9L); vo.setStatus(state); return vo;
                case "selectById": if(Thread.currentThread().getName().equals("maintenance-race")) maintenanceLookup.run(); return task;
                case "isOwnedQueued": return owned && state==InventoryExportStatus.QUEUED;
                case "hasExportAccess": return permitted;
                case "startRunning": if(claimed) state=InventoryExportStatus.RUNNING; return claimed?1:0;
                case "selectUpperBound": return 200000L;
                case "saveUpperBound": return live?1:0;
                case "isRunningLive": return live && state==InventoryExportStatus.RUNNING;
                case "selectExportBatch":
                    beforeBatch.run();
                    int offset=(batches++)*1000;
                    assertEquals(offset==0?0L:rows.get(Math.min(offset,rows.size())-1).getId(),a[1]);
                    if(revokeAfterBatch) permitted=false;
                    if(cancelAfterBatch) state=InventoryExportStatus.CANCEL_REQUESTED;
                    return offset>=rows.size()?List.of():rows.subList(offset,Math.min(offset+1000,rows.size()));
                case "updateProgress": return 1;
                case "publishSuccess":
                    storageKey=(String)a[1];
                    if(published) { state=InventoryExportStatus.SUCCEEDED; task.setStatus(state); }
                    if(publishThrows) throw new IllegalStateException("commit response lost");
                    return published?1:0;
                case "rejectQueued": if(rejectFails) throw new IllegalStateException("database unavailable"); state=InventoryExportStatus.REJECTED; return 1;
                case "renewOwnedLeases":
                    assertEquals(task.getRunToken(),((InventoryExportTaskEntity)a[0]).getRunToken()); renewals++; return 1;
                case "reapExpiredTasks": reaped++; return 1;
                case "expireFiles": return 1;
                case "shouldKeepFiles": return keepFiles;
                case "finishUnsuccessful":
                    failCalls++; failureCode=(String)a[1];
                    if(state==InventoryExportStatus.CANCEL_REQUESTED) state=InventoryExportStatus.CANCELLED;
                    else if(state==InventoryExportStatus.RUNNING || state==InventoryExportStatus.QUEUED) state=InventoryExportStatus.FAILED;
                    return 1;
                default: throw new AssertionError(m.getName());
            }
        }));
        ReflectionTestUtils.setField(service,"exportThreadPool",new AbstractExecutorService() {
            public void execute(Runnable r) { assertTrue(committed,"提交前必须已提交事务"); if(reject)throw new RejectedExecutionException(); pending=r; }
            public void shutdown() {} public List<Runnable> shutdownNow(){return List.of();}
            public boolean isShutdown(){return false;} public boolean isTerminated(){return false;}
            public boolean awaitTermination(long t,TimeUnit u){return true;}
        });
    }
    void configureFiles() { if(org.springframework.util.ReflectionUtils.findField(service.getClass(),"exportRoot")==null) return; ReflectionTestUtils.setField(service,"exportRoot",root.toString()); ReflectionTestUtils.setField(service,"minFreeBytes",0L); }
    void execute() { configureFiles(); ReflectionTestUtils.invokeMethod(service,"executeExport",9L); }
    @Test void 提交在事务提交后执行且返回真实任务() {
        assertEquals(9L,service.create(new InventoryExportCreateDto()).getId()); assertNotNull(pending);
    }
    @Test void 空请求返回400而不是空指针() {
        assertEquals(400,assertThrows(ResponseStatusException.class,()->service.create(null)).getStatusCode().value());
    }
    @Test void 执行生成实际xlsx并发布而不是空方法() throws Exception {
        execute(); assertEquals(InventoryExportStatus.SUCCEEDED,state);
        assertTrue(Files.size(root.resolve(storageKey))>0);
    }
    @Test void 失去执行资格不查询流水不发布() {
        owned=false; execute(); assertEquals(0,batches); assertNull(storageKey);
    }
    @Test void 失效权限停止生成并保存公开错误() {
        permitted=false; execute(); assertEquals(InventoryExportStatus.FAILED,state); assertEquals("ACCESS_REVOKED",failureCode); assertEquals(0,batches);
    }
    @Test void 其他worker已抢占不再次执行() {
        claimed=false; execute(); assertEquals(0,batches); assertNull(storageKey);
    }
    @Test void 成功条件更新失败清理已重命名文件() throws Exception {
        published=false; execute(); assertNotEquals(InventoryExportStatus.SUCCEEDED,state);
        try(var files=Files.walk(root)){assertEquals(0,files.filter(Files::isRegularFile).count());}
    }
    @Test void 执行器拒绝落库并在429保留任务ID() {
        reject=true;
        var failure=assertThrows(com.inventory.export.InventoryExportRejectedException.class,()->service.create(new InventoryExportCreateDto()));
        assertEquals(429,failure.getStatusCode().value()); assertEquals(9L,failure.getTaskId()); assertEquals(InventoryExportStatus.REJECTED,state);
        var response=new com.config.Exception.InventoryExportExceptionHandler().rejected(failure);
        assertEquals(9L,response.getBody().getData().get("taskId"));
    }
    @Test void 拒绝状态保存失败不能谎报429() {
        reject=true; rejectFails=true;
        assertEquals(500,assertThrows(ResponseStatusException.class,()->service.create(new InventoryExportCreateDto())).getStatusCode().value());
    }
    @Test void 查询恢复后发现撤权不再写入或发布() { revokeAfterBatch=true; execute(); assertEquals("ACCESS_REVOKED",failureCode); assertNull(storageKey); }
    @Test void 运行中取消按取消终态结束且不发布() { cancelAfterBatch=true; execute(); assertEquals(InventoryExportStatus.CANCELLED,state); assertNull(storageKey); }
    @Test void 磁盘预留空间不足停止并清理() throws Exception {
        configureFiles(); ReflectionTestUtils.setField(service,"minFreeBytes",Long.MAX_VALUE);
        ReflectionTestUtils.invokeMethod(service,"executeExport",9L);
        assertEquals("DISK_LIMIT",failureCode);
        try(var paths=Files.walk(root)){assertEquals(0,paths.filter(Files::isRegularFile).count());}
    }
    @Test void 超过十万行明确失败不悄悄截断() {
        for(long i=1;i<=100001;i++){var row=new InventoryTransactionVO();row.setId(i); rows.add(row);}
        execute(); assertEquals("ROW_LIMIT",failureCode); assertEquals(101,batches); assertNull(storageKey);
    }
    @Test void 发布已提交但响应丢失不能删除成功文件() throws Exception {
        publishThrows=true; execute(); assertEquals(InventoryExportStatus.SUCCEEDED,state); assertTrue(Files.exists(root.resolve(storageKey)));
    }
    @Test void 生成中英文退料字段且按游标跨批查询() throws Exception {
        for(long i=1;i<=1001;i++){
            var row=new InventoryTransactionVO(); row.setId(i); row.setTransactionNo("流水-"+i);
            row.setQuantity(new BigDecimal("1.250")); row.setNote("=1+1 & 中文"); row.setIssueTransactionId(123456789012345678L); rows.add(row);
        }
        execute(); assertEquals(InventoryExportStatus.SUCCEEDED,state); assertEquals(3,batches);
        try(var zip=new java.util.zip.ZipFile(root.resolve(storageKey).toFile())){
            String sheet=new String(zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(sheet.contains("退料")); assertTrue(sheet.contains("=1+1 &amp; 中文")); assertFalse(sheet.contains("<f>"));
            assertTrue(sheet.contains("t=\"n\"><v>1.250</v>")); assertTrue(sheet.contains("123456789012345678"));
            assertEquals(1002,sheet.split("<row ").length-1);
        }
        if(System.getProperty("export.sample")!=null) Files.copy(root.resolve(storageKey),Path.of(System.getProperty("export.sample")),StandardCopyOption.REPLACE_EXISTING);
    }
    @Test void 排队任务续租且文件清理只删除无有效租约的任务目录() throws Exception {
        configureFiles(); service.create(new InventoryExportCreateDto());
        Path folder=root.resolve(task.getId()+"-"+task.getRunToken()); Files.createDirectory(folder); Files.writeString(folder.resolve("inventory.part"),"temporary");
        Path unrelated=root.resolve("unrelated"); Files.createDirectory(unrelated); Files.writeString(unrelated.resolve("keep.txt"),"keep");
        service.renewExportLeases(); service.maintainExportTasks(); service.cleanExportFiles();
        assertEquals(1,renewals); assertEquals(1,reaped); assertTrue(Files.exists(folder.resolve("inventory.part")));
        keepFiles=false; service.renewExportLeases(); service.maintainExportTasks(); service.cleanExportFiles();
        assertFalse(Files.exists(folder)); assertTrue(Files.exists(unrelated.resolve("keep.txt")));
    }
    @Test void 文件目录已存在时拒绝重用且不删除原文件() throws Exception {
        configureFiles(); Path folder=root.resolve(task.getId()+"-"+task.getRunToken()); Files.createDirectory(folder); Files.writeString(folder.resolve("keep.txt"),"keep");
        execute(); assertNotEquals(InventoryExportStatus.SUCCEEDED,state); assertTrue(Files.exists(folder.resolve("keep.txt")));
    }
    @Test void 维护器不能中断复用同一线程的下一项工作() throws Exception {
        configureFiles(); service.create(new InventoryExportCreateDto());
        var entered=new CountDownLatch(1); var finishA=new CountDownLatch(1);
        var observed=new CountDownLatch(1); var inspect=new CountDownLatch(1);
        var startedB=new CountDownLatch(1); var finishB=new CountDownLatch(1);
        var interruptedB=new java.util.concurrent.atomic.AtomicBoolean();
        beforeBatch=()->{entered.countDown();try{assertTrue(finishA.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}};
        maintenanceLookup=()->{observed.countDown();try{assertTrue(inspect.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}};
        var pool=Executors.newSingleThreadExecutor();
        var checker=new Thread(()->{service.maintainExportTasks();service.maintainExportTasks();},"maintenance-race");
        try {
            var first=pool.submit(pending); assertTrue(entered.await(10,TimeUnit.SECONDS));
            checker.start(); assertTrue(observed.await(10,TimeUnit.SECONDS));
            finishA.countDown(); first.get(10,TimeUnit.SECONDS);
            var second=pool.submit(()->{startedB.countDown();try{finishB.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){interruptedB.set(true);}});
            assertTrue(startedB.await(10,TimeUnit.SECONDS)); inspect.countDown(); checker.join(10000);
            assertFalse(checker.isAlive()); finishB.countDown(); second.get(10,TimeUnit.SECONDS);
            assertFalse(interruptedB.get(),"任务A过期的维护查询不能中断已复用线程的任务B");
        } finally { finishA.countDown(); inspect.countDown(); finishB.countDown(); pool.shutdownNow(); checker.join(10000); }
    }
}
