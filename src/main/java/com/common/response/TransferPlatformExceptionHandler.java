package com.common.response;

import com.inventory.transfer.TransferController;
import com.platform.PlatformTenantController;
import com.tenant.controller.WarehouseController;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/** 仓库、调拨和企业生命周期接口的错误响应。 */
@Slf4j
@RestControllerAdvice(assignableTypes = {
        WarehouseController.class, TransferController.class, PlatformTenantController.class
})
public class TransferPlatformExceptionHandler {
    private static final String INTERNAL_MESSAGE = "操作暂时无法完成，请稍后重试";

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result<Void>> status(ResponseStatusException error) {
        // 保留明确的业务状态码，对内部故障使用固定提示。
        int status = error.getStatusCode().value();
        String reason = error.getReason();
        if (status >= 500) reason = INTERNAL_MESSAGE;
        if (reason == null || reason.isBlank()) reason = "请求无法完成";
        return ResponseEntity.status(status).body(Result.fail(status, reason));
    }

    @ExceptionHandler({BindException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<Result<Void>> binding(Exception error) {
        // 参数响应不回显请求原文。
        return ResponseEntity.badRequest().body(Result.fail(400, "请求参数格式有误"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Result<Void>> integrity(DataIntegrityViolationException error) {
        // 数据关系冲突只返回固定业务提示。
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Result.fail(409, "数据编号或关联关系冲突"));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Result<Void>> unexpected(RuntimeException error) {
        // 记录异常类型用于服务端排查，响应不携带异常原文。
        log.error("Warehouse, transfer or platform request failed: {}", error.getClass().getName());
        return ResponseEntity.internalServerError().body(Result.fail(500, INTERNAL_MESSAGE));
    }
}
