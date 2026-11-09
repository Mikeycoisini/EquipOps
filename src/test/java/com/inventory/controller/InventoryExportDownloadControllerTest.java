package com.inventory.controller;

import com.equipops.domain.vo.InventoryExportTaskVO;
import com.inventory.export.InventoryExportDownloadFile;
import com.inventory.service.InventoryExportDownloadService;
import com.config.Exception.InventoryExportExceptionHandler;
import java.io.*;
import java.util.function.Function;
import jakarta.servlet.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class InventoryExportDownloadControllerTest {
    InventoryExportDownloadService service(Function<Long, InventoryExportDownloadFile> open) {
        return new InventoryExportDownloadService() {
            @Override public InventoryExportDownloadFile openDownload(Long taskId) { return open.apply(taskId); }
            @Override public InventoryExportTaskVO cancel(Long id) { throw new UnsupportedOperationException(); }
        };
    }
    static class ReadyResponse extends MockHttpServletResponse {
        boolean ready=true,failWrite,pauseEachWrite;
        WriteListener listener;
        final ServletOutputStream delegate=super.getOutputStream();
        final ServletOutputStream stream=new ServletOutputStream(){
            public boolean isReady(){return ready;}
            public void setWriteListener(WriteListener next){listener=next;}
            public void write(int value)throws IOException{if(failWrite)throw new IOException("client disconnected");delegate.write(value);}
            public void write(byte[] bytes,int offset,int length)throws IOException{
                if(failWrite)throw new IOException("client disconnected");delegate.write(bytes,offset,length);if(pauseEachWrite)ready=false;
            }
        };
        @Override public ServletOutputStream getOutputStream(){return stream;}
        void fire()throws IOException{listener.onWritePossible();}
    }
    MockHttpServletRequest request(){var request=new MockHttpServletRequest();request.setAsyncSupported(true);return request;}
    @Test void 下载设置文件响应头并在回调结束关闭流()throws Exception{
        boolean[] closed={false};var input=new ByteArrayInputStream(new byte[]{1,2,3,4}){@Override public void close(){closed[0]=true;}};
        var controller=new InventoryExportDownloadController(service(id->new InventoryExportDownloadFile(input,"库存流水-1.xlsx",4)));
        var request=request();var response=new ReadyResponse();controller.download(1L,request,response);
        assertTrue(request.isAsyncStarted());assertFalse(closed[0]);response.fire();
        assertArrayEquals(new byte[]{1,2,3,4},response.getContentAsByteArray());assertEquals("4",response.getHeader("Content-Length"));
        assertEquals("no-store",response.getHeader("Cache-Control"));assertTrue(response.getHeader("Content-Disposition").contains("filename*="));assertTrue(closed[0]);
    }
    @Test void 非数字ID返回统一400()throws Exception{
        var mvc=MockMvcBuilders.standaloneSetup(new InventoryExportDownloadController(service(id->{throw new AssertionError();}))).setControllerAdvice(new InventoryExportExceptionHandler()).build();
        mvc.perform(get("/api/inventory/exports/abc/file").with(request -> {request.setAsyncSupported(true); return request;})).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
    }
    @Test void 客户端写入失败仍关闭输入句柄()throws Exception{
        boolean[] closed={false};var input=new ByteArrayInputStream(new byte[]{1,2,3,4}){@Override public void close(){closed[0]=true;}};
        var controller=new InventoryExportDownloadController(service(id->new InventoryExportDownloadFile(input,"库存流水-1.xlsx",4)));
        var response=new ReadyResponse();response.failWrite=true;controller.download(1L,request(),response);response.fire();
        assertTrue(closed[0]);assertEquals(500,response.getStatus());
    }
    @Test void 背压时不读取文件恢复可写后分块继续()throws Exception{
        byte[] expected=new byte[40000];for(int i=0;i<expected.length;i++)expected[i]=(byte)i;
        int[] closed={0};var input=new ByteArrayInputStream(expected){@Override public void close(){closed[0]++;}};
        var controller=new InventoryExportDownloadController(service(id->new InventoryExportDownloadFile(input,"file.xlsx",expected.length)));
        var response=new ReadyResponse();response.ready=false;response.pauseEachWrite=true;
        controller.download(1L,request(),response);response.fire();assertEquals(40000,input.available());
        response.ready=true;response.fire();assertEquals(40000-16384,input.available());assertEquals(0,closed[0]);
        response.ready=true;response.fire();response.ready=true;response.fire();assertEquals(1,closed[0]);assertArrayEquals(expected,response.getContentAsByteArray());
    }
    @Test void 超时关闭一次资源并结束异步响应()throws Exception{
        int[] closed={0};var input=new ByteArrayInputStream(new byte[]{1,2,3,4}){@Override public void close(){closed[0]++;}};
        var controller=new InventoryExportDownloadController(service(id->new InventoryExportDownloadFile(input,"file.xlsx",4)));
        var request=request();var response=new ReadyResponse();response.ready=false;controller.download(1L,request,response);
        var context=(MockAsyncContext)request.getAsyncContext();
        var listener=(AsyncListener)response.listener;listener.onTimeout(new AsyncEvent(context,request,response));listener.onError(new AsyncEvent(context,request,response,new IOException("late failure")));
        assertEquals(1,closed[0]);assertEquals(504,response.getStatus());assertFalse(request.isAsyncStarted());
    }
    @Test void 不支持异步时拒绝且不打开文件()throws Exception{
        var controller=new InventoryExportDownloadController(service(id->{throw new AssertionError("must not open");}));
        var error=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.download(1L,new MockHttpServletRequest(),new ReadyResponse()));
        assertEquals(503,error.getStatusCode().value());
    }
}
