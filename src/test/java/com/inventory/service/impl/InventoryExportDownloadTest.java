package com.inventory.service.impl;

import com.auth.model.TenantAccess;
import com.auth.service.CurrentAccessService;
import com.equipops.domain.entity.TenantEntity;
import com.equipops.domain.enums.InventoryExportStatus;
import com.inventory.export.*;
import com.inventory.mapper.InventoryExportDownloadMapper;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class InventoryExportDownloadTest {
    @TempDir Path root;
    InventoryExportDownloadServiceImpl service;InventoryExportDownloadRecord task;
    int accessCalls,reads,missingUpdates;boolean access=true,lateAccess=true,downloadable=true,failMark,expireLate;
    Path file;
    @BeforeEach void setup()throws Exception{
        task=new InventoryExportDownloadRecord();task.setId(9L);task.setTenantId(7L);task.setCreatedBy(4L);
        task.setStatus(InventoryExportStatus.SUCCEEDED);task.setExpired(false);task.setRunToken(UUID.randomUUID().toString());
        task.setStorageKey("9-"+task.getRunToken()+"/inventory.xlsx");task.setFileSize(4L);
        file=root.resolve(task.getStorageKey());Files.createDirectories(file.getParent());Files.write(file,new byte[]{1,2,3,4});
        service=new InventoryExportDownloadServiceImpl();
        ReflectionTestUtils.setField(service,"downloadGate",new InventoryDownloadGate(8,2));
        ReflectionTestUtils.setField(service,"fileAccess",new InventoryExportFileAccess());
        var tenant=new TenantEntity();tenant.setId(7L);
        ReflectionTestUtils.setField(service,"exportRoot",root.toString());
        ReflectionTestUtils.setField(service,"currentAccessService",proxy(CurrentAccessService.class,(p,m,a)->{
            accessCalls++;if(!access||accessCalls>1&&!lateAccess)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"成员资格失效");return new TenantAccess(4L,tenant,3L,Set.of());}));
        ReflectionTestUtils.setField(service,"downloadMapper",proxy(InventoryExportDownloadMapper.class,(p,m,a)->switch(m.getName()){
            case "selectOwnDownload"->{reads++;assertArrayEquals(new Object[]{7L,4L,9L},a);yield task;}
            case "canDownload"->{if(expireLate)task.setExpired(true);yield downloadable&&!expireLate;}
            case "markFileMissing"->{missingUpdates++;if(failMark)throw new IllegalStateException("database unavailable");yield 1;}
            default->throw new AssertionError(m.getName());
        }));
    }
    <T>T proxy(Class<T> t,InvocationHandler h){return t.cast(Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{t},h));}
    void rejects(int code){assertEquals(code,assertThrows(ResponseStatusException.class,()->service.openDownload(9L)).getStatusCode().value());}
    @Test void 独立实现返回已打开的流并由调用者关闭()throws Exception{var opened=service.openDownload(9L);assertEquals("库存流水-9.xlsx",opened.fileName());assertEquals(4,opened.fileSize());assertArrayEquals(new byte[]{1,2,3,4},opened.input().readAllBytes());opened.close();assertThrows(java.io.IOException.class,()->opened.input().read());assertEquals(2,accessCalls);}
    @Test void 非本人或其他企业与不存在统一404(){task=null;rejects(404);}
    @Test void 无效身份在查任务前被拒绝(){access=false;rejects(403);assertEquals(0,reads);}
    @Test void 打开文件后撤权关闭句柄且不交付下载()throws Exception{lateAccess=false;rejects(403);Files.delete(file);assertFalse(Files.exists(file));}
    @Test void 已过期成功任务或EXPIRED状态返回410(){task.setExpired(true);rejects(410);task.setStatus(InventoryExportStatus.EXPIRED);rejects(410);}
    @Test void 非成功状态统一409(){for(var status:List.of(InventoryExportStatus.QUEUED,InventoryExportStatus.RUNNING,InventoryExportStatus.CANCEL_REQUESTED,InventoryExportStatus.FAILED,InventoryExportStatus.CANCELLED,InventoryExportStatus.REJECTED)){task.setStatus(status);rejects(409);}}
    @Test void 缺失文件返回409并记录错误()throws Exception{Files.delete(file);rejects(409);assertEquals(1,missingUpdates);}
    @Test void 目录穿越与绝对路径不能读取(){for(var key:List.of("../secret.xlsx",root.resolve("secret.xlsx").toString(),"9-other/inventory.xlsx")){task.setStorageKey(key);rejects(409);}}
    @Test void 文件大小不符不能伪装下载成功(){task.setFileSize(100L);rejects(409);}
    @Test void 缺失记录失败不能声称错误已保存()throws Exception{Files.delete(file);failMark=true;rejects(500);}
    @Test void 发布状态晚到变化后不能交付流()throws Exception{downloadable=false;rejects(409);Files.delete(file);}
    @Test void 非法ID返回400且不查任务(){for(Long id:Arrays.asList(null,0L,-1L))assertEquals(400,assertThrows(ResponseStatusException.class,()->service.openDownload(id)).getStatusCode().value());assertEquals(0,reads);}
    @Test void 打开句柄后到期仍返回410而非交付文件()throws Exception{expireLate=true;rejects(410);Files.delete(file);}
    @Test void 同用户超过两个打开下载必须立即429且关闭后恢复()throws Exception{
        try(var first=service.openDownload(9L);var second=service.openDownload(9L)){
            rejects(429);
        }
        try(var again=service.openDownload(9L)){assertEquals(4,again.fileSize());}
    }
    @Test void Windows下载期间任务目录不能被替换关闭后可以移动()throws Exception{
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path directory=file.getParent(),moved=root.resolve("moved");
        try(var opened=service.openDownload(9L)){
            assertThrows(java.io.IOException.class,()->Files.move(directory,moved));
            assertArrayEquals(new byte[]{1,2,3,4},opened.input().readAllBytes());
        }finally{if(Files.exists(moved))Files.move(moved,directory);}
        Files.move(directory,moved);Files.move(moved,directory);
    }
}
