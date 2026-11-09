package com.config.Exception;

import com.common.response.BusinessExceptionHandler;
import com.common.response.Result;
import com.inventory.controller.InventoryExportController;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 只为导出入口接入既有业务异常响应，不改变其他Controller。 */
@RestControllerAdvice(assignableTypes = {InventoryExportController.class,com.inventory.controller.InventoryExportDownloadController.class})
public class InventoryExportExceptionHandler extends BusinessExceptionHandler {
    @ExceptionHandler(com.inventory.export.InventoryExportRejectedException.class)
    public ResponseEntity<Result<java.util.Map<String,Long>>> rejected(com.inventory.export.InventoryExportRejectedException exception) {
        return ResponseEntity.status(429).body(new Result<>(429, exception.getReason(),
                java.util.Map.of("taskId",exception.getTaskId()),System.currentTimeMillis()));
    }
    /** JSON缺失、枚举或时间格式有误均返回400，不暴露内部解析异常。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> unreadable(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(Result.fail(400, "导出筛选参数格式有误"));
    }
    /** URL路径类型、查询枚举和数字绑定失败统一返回400，不泄露解析堆栈。 */
    @ExceptionHandler({org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.validation.BindException.class})
    public ResponseEntity<Result<Void>> queryBinding(Exception exception) {
        return ResponseEntity.badRequest().body(Result.fail(400,"导出查询参数格式有误"));
    }
}
