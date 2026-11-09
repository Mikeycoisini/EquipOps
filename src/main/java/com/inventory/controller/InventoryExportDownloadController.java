package com.inventory.controller;

import com.common.response.Result;
import com.equipops.domain.vo.InventoryExportTaskVO;
import com.inventory.service.InventoryExportDownloadService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.AsyncContext;
import com.inventory.export.InventoryDownloadTransfer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.web.bind.annotation.*;

/** 独立文件下载入口；验证完成并打开句柄后才设置成功响应头。 */
@RestController
@RequestMapping("/api/inventory/exports")
@RequiredArgsConstructor
public class InventoryExportDownloadController {
    private final InventoryExportDownloadService downloadService;
    @Value("${inventory.export.download.timeout-ms:120000}")
    private long downloadTimeoutMillis = 120000;

    /** 排队取消已完成时返回200；运行中只确认收到取消请求，返回202。 */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Result<InventoryExportTaskVO>> cancel(@PathVariable("id") Long id) {
        InventoryExportTaskVO task = downloadService.cancel(id);
        boolean pending = task.getStatus() == com.equipops.domain.enums.InventoryExportStatus.CANCEL_REQUESTED;
        return ResponseEntity.status(pending ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(Result.success(pending ? "取消请求已提交" : "导出任务已取消", task));
    }

    @GetMapping("/{id}/file")
    public void download(@PathVariable("id") Long id,HttpServletRequest request,HttpServletResponse response) throws IOException {
        // 1. 必须支持异步非阻塞输出；配置错误或不支持时拒绝，不退回同步慢连接传输。
        if (!request.isAsyncSupported() || downloadTimeoutMillis < 1) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"当前请求暂不支持文件下载");
        var file = downloadService.openDownload(id);
        AsyncContext context = null;
        InventoryDownloadTransfer transfer = null;
        boolean registered = false;
        try {
            // 2. 所有身份和文件检查成功后才设置附件头，内容长度来自已打开文件。
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setContentLengthLong(file.fileSize());
            response.setHeader("Content-Disposition",ContentDisposition.attachment().filename(file.fileName(),StandardCharsets.UTF_8).build().toString());
            response.setHeader("Cache-Control","no-store");
            response.setHeader("X-Content-Type-Options","nosniff");
            // 3. 用WriteListener响应可写事件，背压时交还请求线程；超时回调关闭流、目录和名额。
            context = request.startAsync(request,response);
            context.setTimeout(downloadTimeoutMillis);
            var output = response.getOutputStream();
            transfer = new InventoryDownloadTransfer(file,context,response,output);
            context.addListener(transfer);
            output.setWriteListener(transfer);
            registered = true;
        } finally {
            // 4. 回调安装失败时仍关闭全部资源；安装成功后统一由回调幂等释放。
            if (!registered) {
                if (transfer != null) transfer.onError(new IOException("下载回调安装失败"));
                else {
                    try { file.close(); }
                    finally { if (context != null) try { context.complete(); } catch (IllegalStateException completed) { /* 容器可能已结束请求。 */ } }
                }
            }
        }
    }
}
