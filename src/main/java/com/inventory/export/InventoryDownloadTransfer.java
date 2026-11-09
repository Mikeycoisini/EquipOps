package com.inventory.export;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 非阻塞写回调：客户端暂时不可写就交还容器，超时/断开均关闭输入流与名额。 */
public final class InventoryDownloadTransfer implements WriteListener,AsyncListener {
    private static final Logger LOGGER=LoggerFactory.getLogger(InventoryDownloadTransfer.class);
    private final InventoryExportDownloadFile file;
    private final AsyncContext context;
    private final HttpServletResponse response;
    private final ServletOutputStream output;
    private final byte[] buffer=new byte[16384];
    private final AtomicBoolean finished=new AtomicBoolean();
    private long sent;
    public InventoryDownloadTransfer(InventoryExportDownloadFile file,AsyncContext context,HttpServletResponse response,ServletOutputStream output){this.file=file;this.context=context;this.response=response;this.output=output;}

    @Override public void onWritePossible() {
        // 1. 只在容器确认可写时读取固定小块；发生背压立即返回，不等待客户端恢复。
        try {
            while(!finished.get() && output.isReady()) {
                int count=file.input().read(buffer,0,(int)Math.min(buffer.length,file.fileSize()-sent));
                if(count<0 || count==0) {
                    if(sent!=file.fileSize())throw new IOException("Export stream ended early");
                    finish(null,false,true);return;
                }
                if(finished.get())return;
                output.write(buffer,0,count);sent+=count;
                // 2. 按已验证文件大小结束；不为确认EOF额外等待另一次可写事件。
                if(sent==file.fileSize()){finish(null,false,true);return;}
            }
        } catch(Exception failure) {finish(failure,false,true);}
    }
    @Override public void onError(Throwable failure){finish(failure,false,true);}
    @Override public void onError(AsyncEvent event){finish(event.getThrowable(),false,true);}
    @Override public void onTimeout(AsyncEvent event){finish(null,true,true);}
    @Override public void onComplete(AsyncEvent event){finish(null,false,false);}
    @Override public void onStartAsync(AsyncEvent event){event.getAsyncContext().addListener(this);}

    private void finish(Throwable failure,boolean timeout,boolean complete) {
        // 1. 多个回调竞争结束时只有首次关闭资源；输入流关闭会归还并发名额和目录句柄。
        if(!finished.compareAndSet(false,true))return;
        try {file.close();}catch(Exception close){LOGGER.warn("下载资源关闭失败",close);}
        // 2. 未开始发送时返回固定错误状态；已经发送的响应不能伪造完整成功，完成连接终止传输。
        try {
            if(!response.isCommitted() && (timeout || failure!=null)) {
                response.reset();response.setStatus(timeout?504:500);response.setHeader("Cache-Control","no-store");
            }
        } catch(IllegalStateException committedDuringReset) {LOGGER.debug("下载响应已提交，结束当前传输");}
        finally {
            if(failure!=null)LOGGER.debug("下载连接结束",failure);
            // 3. 即使响应在检查与reset之间提交，也始终结束异步响应；容器完成事件不重复complete。
            if(complete)try{context.complete();}catch(IllegalStateException alreadyCompleted){LOGGER.debug("下载响应已经完成");}
        }
    }
}
