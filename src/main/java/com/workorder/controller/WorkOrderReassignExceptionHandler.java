package com.workorder.controller;

import com.common.response.Result;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** 厂区管理入口保留业务错误语义，数据库及内部异常不返回正文。 */
@RestControllerAdvice(assignableTypes = {WorkOrderReassignController.class})
public class WorkOrderReassignExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result<Void>> status(ResponseStatusException error) {
        // 1. 保留明确业务状态；内部失败的自由文本不能进入响应。
        int status = error.getStatusCode().value();
        return ResponseEntity.status(status).body(Result.fail(status,
                status >= 500 ? "操作暂时无法完成，请稍后重试" : error.getReason()));
    }

    @ExceptionHandler({org.springframework.validation.BindException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<Result<Void>> binding(Exception error) {
        // 2. 参数异常使用固定提示，不回显请求体或反序列化异常正文。
        return ResponseEntity.badRequest().body(Result.fail(400, "改派参数格式有误"));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Result<Void>> internal(RuntimeException error) {
        // 3. 未预期失败统一500，不能伪装为业务成功或暴露SQL。
        return ResponseEntity.internalServerError().body(Result.fail(500, "操作暂时无法完成，请稍后重试"));
    }
}
