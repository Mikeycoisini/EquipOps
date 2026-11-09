package com.audit.controller;

import com.common.response.Result;
import com.tenant.controller.TenantMemberController;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** 审计与成员入口保留业务错误，内部失败只返回固定提示，避免暴露SQL或凭据。 */
@RestControllerAdvice(assignableTypes={AuditEventController.class,TenantMemberController.class})
public class MemberAuditExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result<Void>> status(ResponseStatusException error) {
        // 1. 保留已有业务状态；2. 内部错误不向客户端返回原始原因。
        int status = error.getStatusCode().value();
        // 3. 业务原因由服务明确给出，5xx统一脱敏。
        return ResponseEntity.status(status).body(Result.fail(status, status >= 500 ? "操作暂时无法完成，请稍后重试" : error.getReason()));
    }

    @ExceptionHandler({org.springframework.validation.BindException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<Result<Void>> binding(Exception error) {
        // 固定参数提示，不回显请求体、非法枚举原文或解析异常。
        return ResponseEntity.badRequest().body(Result.fail(400,"审计或成员参数格式有误"));
    }

    @ExceptionHandler({DataAccessException.class,RuntimeException.class})
    public ResponseEntity<Result<Void>> internal(RuntimeException error) {
        // 所有未预期内部故障均保留500，不将数据库失败冒充业务成功或参数错误。
        return ResponseEntity.internalServerError().body(Result.fail(500,"操作暂时无法完成，请稍后重试"));
    }
}
